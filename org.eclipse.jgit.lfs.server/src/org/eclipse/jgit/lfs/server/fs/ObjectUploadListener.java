/*
 * Copyright (C) 2015, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lfs.server.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.http.HttpStatus;
import org.eclipse.jgit.lfs.errors.CorruptLongObjectException;
import org.eclipse.jgit.lfs.internal.AtomicObjectOutputStream;
import org.eclipse.jgit.lfs.lib.AnyLongObjectId;
import org.eclipse.jgit.lfs.lib.Constants;

/**
 * Handle asynchronous object upload.
 *
 * @since 4.6
 */
public class ObjectUploadListener implements ReadListener {

	private static final Logger LOG = Logger
			.getLogger(ObjectUploadListener.class.getName());

	private final AsyncContext context;

	private final HttpServletResponse response;

	private final ServletInputStream in;

	private final ReadableByteChannel inChannel;

	private final AtomicObjectOutputStream out;

	private WritableByteChannel channel;

	private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192);

	private final Path path;

	private long uploaded;

	private Callback callback;

	/**
	 * Callback invoked after object upload completed.
	 *
	 * @since 5.1.7
	 */
	public interface Callback {
		/**
		 * Notified after object upload completed.
		 *
		 * @param path
		 *            path to the object on the backend
		 * @param size
		 *            uploaded size in bytes
		 */
		void uploadCompleted(String path, long size);
	}

	/**
	 * Constructor for ObjectUploadListener.
	 *
	 * @param repository
	 *            the repository storing large objects
	 * @param context
	 *            a {@link jakarta.servlet.AsyncContext} object.
	 * @param request
	 *            a {@link jakarta.servlet.http.HttpServletRequest} object.
	 * @param response
	 *            a {@link jakarta.servlet.http.HttpServletResponse} object.
	 * @param id
	 *            a {@link org.eclipse.jgit.lfs.lib.AnyLongObjectId} object.
	 * @throws java.io.FileNotFoundException
	 *             if file wasn't found
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 * @since 7.0
	 */
	public ObjectUploadListener(FileLfsRepository repository,
			AsyncContext context, HttpServletRequest request,
			HttpServletResponse response, AnyLongObjectId id)
					throws FileNotFoundException, IOException {
		this.context = context;
		this.response = response;
		this.in = request.getInputStream();
		this.inChannel = Channels.newChannel(in);
		this.out = repository.getOutputStream(id);
		this.channel = Channels.newChannel(out);
		this.path = repository.getPath(id);
		this.uploaded = 0L;
		response.setContentType(Constants.CONTENT_TYPE_GIT_LFS_JSON);
	}

	/**
	 * Set the callback to invoke after upload completed.
	 *
	 * @param callback
	 *            the callback
	 * @return {@code this}.
	 * @since 5.1.7
	 */
	public ObjectUploadListener setCallback(Callback callback) {
		this.callback = callback;
		return this;
	}

	/**
	 * {@inheritDoc}
	 *
	 * Writes all the received data to the output channel
	 */
	@Override
	public void onDataAvailable() throws IOException {
		while (in.isReady()) {
			if (inChannel.read(buffer) > 0) {
				buffer.flip();
				uploaded += Integer.valueOf(channel.write(buffer)).longValue();
				buffer.compact();
			} else {
				buffer.flip();
				while (buffer.hasRemaining()) {
					uploaded += Integer.valueOf(channel.write(buffer))
							.longValue();
				}
				close();
				return;
			}
		}
	}

	@Override
	public void onAllDataRead() throws IOException {
		close();
	}

	/**
	 * Close resources held by this listener
	 *
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 */
	protected void close() throws IOException {
		try {
			inChannel.close();
			channel.close();
			// TODO check if status 200 is ok for PUT request, HTTP foresees 204
			// for successful PUT without response body
			if (!response.isCommitted()) {
				response.setStatus(HttpServletResponse.SC_OK);
			}
			if (callback != null) {
				callback.uploadCompleted(path.toString(), uploaded);
			}
		} finally {
			context.complete();
		}
	}

	@Override
	public void onError(Throwable e) {
		try {
			out.abort();
			inChannel.close();
			channel.close();
			int status;
			if (e instanceof CorruptLongObjectException) {
				status = HttpStatus.SC_BAD_REQUEST;
				LOG.log(Level.WARNING, e.getMessage(), e);
			} else {
				status = HttpStatus.SC_INTERNAL_SERVER_ERROR;
				LOG.log(Level.SEVERE, e.getMessage(), e);
			}
			FileLfsServlet.sendError(response, status, e.getMessage());
		} catch (IOException ex) {
			LOG.log(Level.SEVERE, ex.getMessage(), ex);
		}
	}
}
