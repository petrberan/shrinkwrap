/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shrinkwrap.impl.base.asset;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.shrinkwrap.api.asset.Asset;

/**
 * An {@link Asset} implementation that buffers stream content either in memory or on disk
 * depending on the size. This prevents OutOfMemoryErrors when importing large archive entries.
 * <p>
 * Small entries (below threshold) are kept in memory for performance.
 * Large entries (at or above threshold) are buffered to temporary disk files for streaming.
 * <p>
 * The threshold can be configured via the system property "shrinkwrap.buffer.threshold.bytes".
 * Default threshold is 100 MB (104,857,600 bytes).
 *
 * @author ShrinkWrap Team
 */
public class StreamBufferedAsset implements Asset {
    private static final Logger log = Logger.getLogger(StreamBufferedAsset.class.getName());

    /**
     * System property name for configuring the buffering threshold
     */
    private static final String PROPERTY_BUFFER_THRESHOLD = "shrinkwrap.buffer.threshold.bytes";

    /**
     * Default threshold: 100 MB in bytes
     */
    private static final long DEFAULT_THRESHOLD_BYTES = 100L * 1024 * 1024;

    /**
     * The configured threshold in bytes
     */
    private static final long THRESHOLD_BYTES = getConfiguredThreshold();

    /**
     * Buffer size for reading/writing streams
     */
    private static final int BUFFER_SIZE = 8192;

    /**
     * In-memory byte array for small assets (may be null if buffered to disk)
     */
    private final byte[] memoryBuffer;

    /**
     * Temporary file for large assets (may be null if buffered in memory)
     */
    private final File tempFile;

    /**
     * Whether this asset is buffered in memory (true) or on disk (false)
     */
    private final boolean inMemory;

    /**
     * Creates a new StreamBufferedAsset by reading from the provided InputStream.
     * The stream will be fully consumed and can be closed after this constructor returns.
     *
     * @param inputStream the stream to read from
     * @throws IOException if an error occurs while reading the stream
     */
    public StreamBufferedAsset(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }

        // First, try to buffer in memory up to the threshold
        ByteArrayOutputStream memBuffer = new ByteArrayOutputStream(BUFFER_SIZE);
        byte[] chunk = new byte[BUFFER_SIZE];
        int bytesRead;
        long totalBytes = 0;
        boolean exceededThreshold = false;

        while ((bytesRead = inputStream.read(chunk)) != -1) {
            totalBytes += bytesRead;

            // Check if we've exceeded the threshold
            if (totalBytes > THRESHOLD_BYTES) {
                exceededThreshold = true;
                break;
            }

            memBuffer.write(chunk, 0, bytesRead);
        }

        if (!exceededThreshold) {
            // Small enough to keep in memory
            this.memoryBuffer = memBuffer.toByteArray();
            this.tempFile = null;
            this.inMemory = true;

            if (log.isLoggable(Level.FINE)) {
                log.fine("Buffered " + totalBytes + " bytes in memory (threshold: " + THRESHOLD_BYTES + ")");
            }
        } else {
            // Too large, need to buffer to disk
            this.memoryBuffer = null;
            this.tempFile = File.createTempFile("shrinkwrap-", ".tmp");
            this.tempFile.deleteOnExit();
            this.inMemory = false;

            try (FileOutputStream fos = new FileOutputStream(tempFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE)) {

                // Write what we've already read to the file
                memBuffer.writeTo(bos);

                // Write the chunk that caused us to exceed the threshold
                bos.write(chunk, 0, bytesRead);

                // Continue reading and writing the rest of the stream
                while ((bytesRead = inputStream.read(chunk)) != -1) {
                    bos.write(chunk, 0, bytesRead);
                    totalBytes += bytesRead;
                }

                bos.flush();
            }

            if (log.isLoggable(Level.FINE)) {
                log.fine("Buffered " + totalBytes + " bytes to temp file: " + tempFile.getAbsolutePath()
                    + " (threshold: " + THRESHOLD_BYTES + ")");
            }
        }
    }

    /**
     * Opens a new stream to read the buffered content.
     * If buffered in memory, returns a ByteArrayInputStream.
     * If buffered to disk, returns a FileInputStream.
     *
     * @return a new InputStream to read the buffered content
     */
    @Override
    public InputStream openStream() {
        if (inMemory) {
            return new ByteArrayInputStream(memoryBuffer);
        } else {
            try {
                return new BufferedInputStream(new FileInputStream(tempFile), BUFFER_SIZE);
            } catch (IOException e) {
                throw new RuntimeException("Failed to open stream from temp file: " + tempFile, e);
            }
        }
    }

    /**
     * Cleans up resources, particularly temporary files if buffered to disk.
     * This method is called during finalization, but callers can invoke it explicitly
     * when the asset is no longer needed.
     */
    public void cleanup() {
        if (!inMemory && tempFile != null && tempFile.exists()) {
            if (tempFile.delete()) {
                if (log.isLoggable(Level.FINE)) {
                    log.fine("Deleted temp file: " + tempFile.getAbsolutePath());
                }
            } else {
                log.warning("Failed to delete temp file: " + tempFile.getAbsolutePath());
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            cleanup();
        } finally {
            super.finalize();
        }
    }

    /**
     * Returns whether this asset is buffered in memory (true) or on disk (false).
     *
     * @return true if in memory, false if on disk
     */
    public boolean isInMemory() {
        return inMemory;
    }

    /**
     * Gets the configured threshold from system properties, or returns the default if not set.
     *
     * @return the threshold in bytes
     */
    private static long getConfiguredThreshold() {
        String thresholdProp = System.getProperty(PROPERTY_BUFFER_THRESHOLD);
        if (thresholdProp != null) {
            try {
                long threshold = Long.parseLong(thresholdProp);
                if (threshold < 0) {
                    log.warning("Invalid threshold value (negative): " + threshold + ", using default: " + DEFAULT_THRESHOLD_BYTES);
                    return DEFAULT_THRESHOLD_BYTES;
                }
                log.info("Using configured buffer threshold: " + threshold + " bytes");
                return threshold;
            } catch (NumberFormatException e) {
                log.warning("Invalid threshold property value: " + thresholdProp + ", using default: " + DEFAULT_THRESHOLD_BYTES);
                return DEFAULT_THRESHOLD_BYTES;
            }
        }
        return DEFAULT_THRESHOLD_BYTES;
    }

    @Override
    public String toString() {
        return StreamBufferedAsset.class.getSimpleName() + " [inMemory=" + inMemory
            + (inMemory ? ", size=" + memoryBuffer.length : ", tempFile=" + tempFile) + "]";
    }
}
