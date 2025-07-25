package org.icij.extract;

import org.icij.concurrent.SealableLatch;
import org.icij.event.Notifiable;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class ScannerVisitor extends SimpleFileVisitor<Path> implements Callable<Long> {
    public static final String FOLLOW_SYMLINKS = "followSymlinks";
    public static final String MAX_DEPTH = "maxDepth";
    public static final String QUEUE_FULL_TIMEOUT = "queueFullTimeout";
    public static final String QUEUE_FULL_STOP = "queueFullStop";
    private Logger logger = LoggerFactory.getLogger(getClass());
    private final ArrayDeque<PathMatcher> includeMatchers = new ArrayDeque<>();
    private final ArrayDeque<PathMatcher> excludeMatchers = new ArrayDeque<>();

    private final Path path;

    private final BlockingQueue<Path> queue;

    private boolean followLinks = false;
    private int maxDepth = Integer.MAX_VALUE;
    private int queueFullTimeout = 60;
    private boolean queueFullStop = false;

    private SealableLatch latch;
    private Notifiable notifiable;
    private long queued = 0;

    /**
     * Instantiate a new task for scanning the given path.
     *
     * @param path the path to scan
     */
    public ScannerVisitor(final Path path, final BlockingQueue<Path> queue, Options<String> options) {
        this.path = path;
        this.queue = queue;
        options.ifPresent(FOLLOW_SYMLINKS, o -> o.parse().asBoolean()).ifPresent(this::followSymLinks);
        options.ifPresent(MAX_DEPTH, o -> o.parse().asInteger()).ifPresent(this::setMaxDepth);
        options.ifPresent(QUEUE_FULL_TIMEOUT, o -> o.parse().asInteger()).ifPresent(this::setQueueFullTimeout);
        options.ifPresent(QUEUE_FULL_STOP, o -> o.parse().asBoolean()).ifPresent(this::setQueueFullStop);
    }

    public ScannerVisitor withMonitor(Notifiable monitor) { notifiable = monitor; return this;}
    public ScannerVisitor withLatch(SealableLatch latch) { this.latch = latch; return this;}

    /**
     * Recursively walks the file tree of a directory. When hiswalking is finished or stopped by an exception, the
     * latch is sealed and signalled.
     *
     * @return the path at which scanning started
     */
    @Override
    public Long call() throws Exception {
        final Set<FileVisitOption> options;

        if (followLinks) {
            options = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
        } else {
            options = EnumSet.noneOf(FileVisitOption.class);
        }

        logger.info(String.format("Starting scan of: \"%s\".", path));
        try {
            Files.walkFileTree(path, options, maxDepth, this);
        } catch (IOException e) {
            logger.error(String.format("Error while scanning path: \"%s\".", path), e);
            throw e;
        } finally {
            if (null != latch) {
                latch.seal();
                latch.signal();
            }
        }

        logger.info(String.format("Completed scan of: \"%s\".", path));
        return queued;
    }

    /**
     * Queue a result from the scanner. Blocks until a queue slot is available.
     *
     * @throws InterruptedException if interrupted while waiting for a queue slot
     */
    void queue(final Path file, final BasicFileAttributes attributes) throws InterruptedException {
        if(queue.offer(file, queueFullTimeout, TimeUnit.SECONDS )){
            queued++;

            if (null != latch) {
                latch.signal();
            }

            if (null != notifiable) {
                notifiable.notifyListeners(file);
            }
        }
        else{
            onQueueFull(file, attributes);
        }
    }

    void onQueueFull(final Path file, final BasicFileAttributes attributes) throws InterruptedException {
        logger.warn(String.format("Queue is full. Try with an additional taskWorker or update queueCapacity. Retrying: \"%s\".", file));
        if(queueFullStop){
            throw new InterruptedException("Queue is full, stopping scan.");
        }
        this.queue(file,attributes);
    }
    /**
     * Add a path matcher for files to exclude.
     *
     * @param matcher the matcher
     */
    void exclude(final PathMatcher matcher) {
        excludeMatchers.add(matcher);
    }

    /**
     * Add a path matcher for files to include.
     *
     * @param matcher the matcher
     */
    void include(final PathMatcher matcher) {
        includeMatchers.add(matcher);
    }

    /**
     * Check whether a path should be excluded.
     *
     * @param path path to check
     * @return whether the path should be excluded
     */
    boolean shouldExclude(final Path path) {
        return matches(path, excludeMatchers);
    }

    /**
     * Check whether a path should be included.
     *
     * @param path path to check
     * @return whether the path should be included
     */
    boolean shouldInclude(final Path path) {
        return includeMatchers.size() == 0 || matches(path, includeMatchers);
    }

    /**
     * Check whether a path matches any of the given matchers.
     *
     * @param path path to check
     * @return whether the path matches
     */
    private boolean matches(final Path path, final ArrayDeque<PathMatcher> matchers) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            logger.warn("Scanner interrupted. Terminating job.");
            return FileVisitResult.TERMINATE;
        }

        if (shouldExclude(directory)) {
            return FileVisitResult.SKIP_SUBTREE;
        }

        logger.info(String.format("Entering directory: \"%s\".", directory));
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            logger.warn("Scanner interrupted. Terminating job.");
            return FileVisitResult.TERMINATE;
        }

        // From the documentation:
        // "When following links, and the attributes of the target cannot be read, then this method attempts to
        // parse the BasicFileAttributes of the link."
        if (attributes.isSymbolicLink()) {
            if (followLinks) {
                logger.warn(String.format("Unable to read attributes of symlink target: \"%s\". Skipping.", file));
            }

            return FileVisitResult.CONTINUE;
        }

        // Only skip the file if all of the include matchers return false.
        if (!shouldInclude(file)) {
            return FileVisitResult.CONTINUE;
        }

        if (shouldExclude(file)) {
            return FileVisitResult.CONTINUE;
        }

        try {
            queue(file, attributes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted. Terminating scanner.");
            return FileVisitResult.TERMINATE;
        } catch (Exception e) {
            logger.error(String.format("Exception while queuing file: \"%s\".", file), e);
            throw e;
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException e) throws IOException {

        // If the file or directory was going to be excluded anyway, suppress the exception.
        // Don't re-throw the error. Scanning must be robust. Just log it.
        if (!shouldExclude(file)) {
            logger.error(String.format("Unable to read attributes of file: \"%s\".", file), e);
        }

        return FileVisitResult.CONTINUE;
    }

    protected BlockingQueue<Path> getQueue() { return queue; }
    private void setMaxDepth(Integer max) { maxDepth = max;}
    private void setQueueFullTimeout(Integer timeout) { queueFullTimeout = timeout;}
    private void setQueueFullStop(Boolean stop) { queueFullStop = stop ;}
    private void followSymLinks(Boolean follow) { followLinks = follow;}

}
