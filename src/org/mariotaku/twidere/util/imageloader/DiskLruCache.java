/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mariotaku.twidere.util.imageloader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.mariotaku.twidere.BuildConfig;
import org.mariotaku.twidere.util.Utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.util.Log;

/**
 * A simple disk LRU bitmap cache to illustrate how a disk cache would be used
 * for bitmap caching. A much more robust and efficient disk LRU cache solution
 * can be found in the ICS source code
 * (libcore/luni/src/main/java/libcore/io/DiskLruCache.java) and is preferable
 * to this simple implementation.
 */
public class DiskLruCache {
	private static final String TAG = "DiskLruCache";
	private static final String CACHE_FILENAME_PREFIX = "cache_";
	private static final int MAX_REMOVALS = 4;
	private static final int INITIAL_CAPACITY = 32;
	private static final float LOAD_FACTOR = 0.75f;

	private final File mCacheDir;
	private int cacheSize = 0;
	private int cacheByteSize = 0;
	private final int maxCacheItemSize = 64; // 64 item default
	private long maxCacheByteSize = 1024 * 1024 * 5; // 5MB default
	private CompressFormat mCompressFormat = CompressFormat.JPEG;
	private int mCompressQuality = 70;

	private final Map<String, String> mLinkedHashMap = Collections.synchronizedMap(new LinkedHashMap<String, String>(
			INITIAL_CAPACITY, LOAD_FACTOR, true));

	/**
	 * A filename filter to use to identify the cache filenames which have
	 * CACHE_FILENAME_PREFIX prepended.
	 */
	private static final FilenameFilter cacheFileFilter = new FilenameFilter() {
		@Override
		public boolean accept(final File dir, final String filename) {
			return filename.startsWith(CACHE_FILENAME_PREFIX);
		}
	};

	/**
	 * Constructor that should not be called directly, instead use
	 * {@link DiskLruCache#openCache(Context, File, long)} which runs some extra
	 * checks before creating a DiskLruCache instance.
	 * 
	 * @param cacheDir
	 * @param maxByteSize
	 */
	private DiskLruCache(final File cacheDir, final long maxByteSize) {
		mCacheDir = cacheDir;
		maxCacheByteSize = maxByteSize;
	}

	/**
	 * Removes all disk cache entries from this instance cache dir
	 */
	public void clearCache() {
		DiskLruCache.clearCache(mCacheDir);
	}

	/**
	 * Checks if a specific key exist in the cache.
	 * 
	 * @param key The unique identifier for the bitmap
	 * @return true if found, false otherwise
	 */
	public boolean containsKey(final String key) {
		// See if the key is in our HashMap
		if (mLinkedHashMap.containsKey(key)) return true;

		// Now check if there's an actual file that exists based on the key
		final String existingFile = createFilePath(mCacheDir, key);
		if (new File(existingFile).exists()) {
			// File found, add it to the HashMap for future use
			put(key, existingFile);
			return true;
		}
		return false;
	}

	/**
	 * Create a constant cache file path using the current cache directory and
	 * an image key.
	 * 
	 * @param key
	 * @return
	 */
	public String createFilePath(final String key) {
		return createFilePath(mCacheDir, key);
	}

	/**
	 * Get an image from the disk cache.
	 * 
	 * @param key The unique identifier for the bitmap
	 * @return The bitmap or null if not found
	 */
	public Bitmap get(final String key) {
		synchronized (mLinkedHashMap) {
			final String file = mLinkedHashMap.get(key);
			if (file != null) {
				if (BuildConfig.DEBUG) {
					Log.d(TAG, "Disk cache hit");
				}
				try {
					final RandomAccessFile raf = new RandomAccessFile(file, "r");
					return BitmapFactory.decodeFileDescriptor(raf.getFD());
				} catch (final IOException e) {
					Log.e(TAG, "getBitmap - " + e);
				}
				// return BitmapFactory.decodeFile(file);
			} else {
				final String existingFile = createFilePath(mCacheDir, key);
				if (new File(existingFile).exists()) {
					put(key, existingFile);
					if (BuildConfig.DEBUG) {
						Log.d(TAG, "Disk cache hit (existing file)");
					}
					try {
						final RandomAccessFile raf = new RandomAccessFile(existingFile, "r");
						return BitmapFactory.decodeFileDescriptor(raf.getFD());
					} catch (final IOException e) {
						Log.e(TAG, "getBitmap - " + e);
					}
					// return BitmapFactory.decodeFile(existingFile);
				}
			}
			return null;
		}
	}

	public File getFile(final String key) {
		synchronized (mLinkedHashMap) {
			final String file = mLinkedHashMap.get(key);
			if (file != null) {
				if (BuildConfig.DEBUG) {
					Log.d(TAG, "Disk cache hit");
				}
				return new File(file);
			} else {
				final String existingFile = createFilePath(mCacheDir, key);
				if (new File(existingFile).exists()) {
					put(key, existingFile);
					if (BuildConfig.DEBUG) {
						Log.d(TAG, "Disk cache hit (existing file)");
					}
					return new File(existingFile);
				}
			}
			return null;
		}
	}

	/**
	 * Add a bitmap to the disk cache.
	 * 
	 * @param key A unique identifier for the bitmap.
	 * @param data The bitmap to store.
	 */
	public void put(final String key, final Bitmap data) {
		if (data == null) return;
		synchronized (mLinkedHashMap) {
			if (mLinkedHashMap.get(key) == null) {
				try {
					final String file = createFilePath(mCacheDir, key);
					if (writeBitmapToFile(data, file)) {
						put(key, file);
						flushCache();
					}
				} catch (final FileNotFoundException e) {
					Log.e(TAG, "Error in put: " + e.getMessage());
				} catch (final IOException e) {
					Log.e(TAG, "Error in put: " + e.getMessage());
				}
			}
		}
	}

	/**
	 * Sets the target compression format and quality for images written to the
	 * disk cache.
	 * 
	 * @param compressFormat
	 * @param quality
	 */
	public void setCompressParams(final CompressFormat compressFormat, final int quality) {
		mCompressFormat = compressFormat;
		mCompressQuality = quality;
	}

	/**
	 * Flush the cache, removing oldest entries if the total size is over the
	 * specified cache size. Note that this isn't keeping track of stale files
	 * in the cache directory that aren't in the HashMap. If the images and keys
	 * in the disk cache change often then they probably won't ever be removed.
	 */
	private void flushCache() {
		Entry<String, String> eldestEntry;
		File eldestFile;
		long eldestFileSize;
		int count = 0;

		while (count < MAX_REMOVALS && (cacheSize > maxCacheItemSize || cacheByteSize > maxCacheByteSize)) {
			eldestEntry = mLinkedHashMap.entrySet().iterator().next();
			eldestFile = new File(eldestEntry.getValue());
			eldestFileSize = eldestFile.length();
			mLinkedHashMap.remove(eldestEntry.getKey());
			eldestFile.delete();
			cacheSize = mLinkedHashMap.size();
			cacheByteSize -= eldestFileSize;
			count++;
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "flushCache - Removed cache file, " + eldestFile + ", " + eldestFileSize);
			}
		}
	}

	private void put(final String key, final String file) {
		mLinkedHashMap.put(key, file);
		cacheSize = mLinkedHashMap.size();
		cacheByteSize += new File(file).length();
	}

	/**
	 * Writes a bitmap to a file. Call
	 * {@link DiskLruCache#setCompressParams(CompressFormat, int)} first to set
	 * the target bitmap compression and format.
	 * 
	 * @param bitmap
	 * @param file
	 * @return
	 */
	private boolean writeBitmapToFile(final Bitmap bitmap, final String file) throws IOException, FileNotFoundException {
		if (bitmap == null) return false;
		OutputStream out = null;
		try {
			final RandomAccessFile raf = new RandomAccessFile(file, "rw");
			out = new FileOutputStream(raf.getFD());
			// out = new BufferedOutputStream(new FileOutputStream(file),
			// ImageLoaderUtils.IO_BUFFER_SIZE);
			return bitmap.compress(mCompressFormat, mCompressQuality, out);
		} finally {
			if (out != null) {
				out.close();
			}
		}
	}

	/**
	 * Removes all disk cache entries from the application cache directory in
	 * the uniqueName sub-directory.
	 * 
	 * @param context The context to use
	 * @param uniqueName A unique cache directory name to append to the app
	 *            cache directory
	 */
	public static void clearCache(final Context context, final String uniqueName) {
		final File cacheDir = getDiskCacheDir(context, uniqueName);
		clearCache(cacheDir);
	}

	/**
	 * Creates a constant cache file path given a target cache directory and an
	 * image key.
	 * 
	 * @param cacheDir
	 * @param key
	 * @return
	 */
	public static String createFilePath(final File cacheDir, final String key) {
		try {
			// Use URLEncoder to ensure we have a valid filename, a tad hacky
			// but it will do for
			// this example
			return cacheDir.getAbsolutePath() + File.separator + CACHE_FILENAME_PREFIX
					+ URLEncoder.encode(key.replace("*", ""), "UTF-8");
		} catch (final UnsupportedEncodingException e) {
			Log.e(TAG, "createFilePath - " + e);
		}

		return null;
	}

	/**
	 * Get a usable cache directory (external if available, internal otherwise).
	 * 
	 * @param context The context to use
	 * @param uniqueName A unique directory name to append to the cache dir
	 * @return The cache dir
	 */
	public static File getDiskCacheDir(final Context context, final String uniqueName) {
		return Utils.getBestCacheDir(context, uniqueName);
	}

	/**
	 * Used to fetch an instance of DiskLruCache.
	 * 
	 * @param context
	 * @param cacheDir
	 * @param maxByteSize
	 * @return
	 */
	public static DiskLruCache openCache(final Context context, final File cacheDir, final long maxByteSize) {
		if (!cacheDir.exists()) {
			cacheDir.mkdir();
		}

		if (cacheDir.isDirectory() && cacheDir.canWrite() && ImageLoaderUtils.getUsableSpace(cacheDir) > maxByteSize)
			return new DiskLruCache(cacheDir, maxByteSize);

		return null;
	}

	/**
	 * Removes all disk cache entries from the given directory. This should not
	 * be called directly, call {@link DiskLruCache#clearCache(Context, String)}
	 * or {@link DiskLruCache#clearCache()} instead.
	 * 
	 * @param cacheDir The directory to remove the cache files from
	 */
	private static void clearCache(final File cacheDir) {
		final File[] files = cacheDir.listFiles(cacheFileFilter);
		for (final File file : files) {
			file.delete();
		}
	}
}
