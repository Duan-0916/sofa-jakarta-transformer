/********************************************************************************
 * Copyright (c) Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: (EPL-2.0 OR Apache-2.0)
 ********************************************************************************/

package org.eclipse.transformer.action.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.eclipse.transformer.TransformException;
import org.eclipse.transformer.action.Action;
import org.eclipse.transformer.action.ActionType;
import org.eclipse.transformer.action.ByteData;
import org.eclipse.transformer.action.InputBuffer;
import org.eclipse.transformer.action.SelectionRule;
import org.eclipse.transformer.action.SignatureRule;
import org.eclipse.transformer.util.FileUtils;
import org.slf4j.Logger;

import aQute.lib.io.IO;

public class ZipActionImpl extends ContainerActionImpl {

	public ZipActionImpl(Logger logger, InputBuffer buffer, SelectionRule selectionRule, SignatureRule signatureRule) {
		super(logger, buffer, selectionRule, signatureRule);
	}

	//

	@Override
	public String getName() {
		return "Zip Action";
	}

	@Override
	public ActionType getActionType() {
		return ActionType.ZIP;
	}

	@Override
	public String getAcceptExtension() {
		return ".zip";
	}

	@Override
	public boolean useStreams() {
		return true;
	}

	//

	@Override
	public void apply(String inputPath, InputStream inputStream, int inputCount, OutputStream outputStream)
		throws TransformException {
		startRecording(inputPath);
		try {
			setResourceNames(inputPath, inputPath);

			/*
			 * Use Zip streams instead of Jar streams. Jar streams automatically
			 * read and consume the manifest, which we don't want.
			 */
			try {
				ZipInputStream zipInputStream = new ZipInputStream(inputStream);
				ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
				try {
					apply(inputPath, zipInputStream, zipOutputStream);
				} finally {
					zipOutputStream.finish();
				}
			} catch (IOException e) {
				throw new TransformException("Failed to complete output [ " + inputPath + " ]", e);
			}
		} finally {
			stopRecording(inputPath);
		}
	}

	protected void apply(String inputPath, ZipInputStream zipInputStream, ZipOutputStream zipOutputStream)
		throws TransformException {

		String prevName = null;
		String inputName = null;
		byte[] copyBuffer = new byte[FileUtils.BUFFER_ADJUSTMENT];

		try {
			for (ZipEntry inputEntry; (inputEntry = zipInputStream
				.getNextEntry()) != null; prevName = inputName, inputName = null) {
				// Sanitize inputName to avoid ZipSlip
				inputName = cleanPath(inputEntry.getName());
				int inputLength = Math.toIntExact(inputEntry.getSize());

				getLogger().debug("[ {}.{} ] [ {} ] Size [ {} ]", getClass().getSimpleName(), "apply", inputName,
					inputLength);

				try {
					Action acceptedAction = acceptAction(inputName);
					if (acceptedAction == null) {
						recordUnaccepted(inputName);
						copy(inputEntry, zipInputStream, zipOutputStream, copyBuffer);
						continue;
					}
					if (!select(inputName)) {
						recordUnselected(acceptedAction, inputName);
						copy(inputEntry, zipInputStream, zipOutputStream, copyBuffer);
						continue;
					}
					if (acceptedAction.useStreams()) {
						/*
						 * Archive type actions are processed using streams,
						 * while non-archive type actions do a full read of the
						 * entry data and process the resulting byte array.
						 * Ideally, a single pattern would be used for both
						 * cases, but that is not possible: A full read of a
						 * nested archive is not possible because the nested
						 * archive can be very large. A read of non-archive data
						 * must be performed, since non-archive data may change
						 * the name associated with the data, and that can only
						 * be determined after reading the data. We do the name
						 * transform work here since the resource can be in a
						 * renamed package.
						 */
						String outputName = cleanPath(acceptedAction.relocateResource(inputName));
						ZipEntry outputEntry = new ZipEntry(outputName);
						outputEntry.setExtra(inputEntry.getExtra());
						outputEntry.setComment(inputEntry.getComment());
						zipOutputStream.putNextEntry(outputEntry);
						acceptedAction.apply(inputName, zipInputStream, inputLength, zipOutputStream);
						// Record the output name since the apply method does
						// not know
						acceptedAction.getLastActiveChanges()
							.setOutputResourceName(outputName);
						recordTransform(acceptedAction, inputName);
						zipOutputStream.closeEntry();
						continue;
					}
					ByteData inputData = acceptedAction.collect(inputName, zipInputStream, inputLength);
					ByteData outputData;
					try {
						outputData = acceptedAction.apply(inputData);
						recordTransform(acceptedAction, inputName);
					} catch (TransformException t) {
						// Fall back and copy
						outputData = inputData;
						getLogger().error(t.getMessage(), t);
					}

					// Sanitize outputName to avoid ZipSlip
					String outputName = cleanPath(outputData.name());
					ZipEntry outputEntry = new ZipEntry(outputName);
					outputEntry.setMethod(inputEntry.getMethod());
					outputEntry.setExtra(inputEntry.getExtra());
					outputEntry.setComment(inputEntry.getComment());
					ByteBuffer buffer = outputData.buffer();
					if (outputEntry.getMethod() == ZipOutputStream.STORED) {
						outputEntry.setSize(buffer.remaining());
						outputEntry.setCompressedSize(buffer.remaining());
						CRC32 crc = new CRC32();
						buffer.mark();
						crc.update(buffer);
						buffer.reset();
						outputEntry.setCrc(crc.getValue());
					}
					zipOutputStream.putNextEntry(outputEntry);
					IO.copy(buffer, zipOutputStream);
					zipOutputStream.closeEntry();
				} catch (Exception e) {
					getLogger().error("Transform failure [ {} ]", inputName, e);
				}
			}
		} catch (IOException e) {
			String message;
			if (inputName != null) {
				// Actively processing an entry.
				message = "Failure while processing [ " + inputName + " ] from [ " + inputPath + " ]";
			} else if (prevName != null) {
				// Moving to a new entry but not the first entry.
				message = "Failure after processing [ " + prevName + " ] from [ " + inputPath + " ]";
			} else {
				// Moving to the first entry.
				message = "Failed to process first entry of [ " + inputPath + " ]";
			}
			throw new TransformException(message, e);
		}
	}

	private final static Pattern PATH_SPLITTER = Pattern.compile("[/\\\\]");

	private String cleanPath(String path) {
		if (path.isEmpty()) {
			return path;
		}
		String normalized;
		try {
			normalized = Paths.get("", PATH_SPLITTER.split(path))
				.normalize()
				.toString()
				.replace('\\', '/');
		} catch (InvalidPathException e) {
			throw new TransformException("invalid zip entry name " + path, e);
		}
		if (normalized.startsWith("..") && //
			((normalized.length() == 2) || (normalized.charAt(2) == '/'))) {
				throw new TransformException("invalid zip entry name " + path);
		}
		char lastChar = path.charAt(path.length() - 1);
		if ((lastChar == '/') || (lastChar == '\\')) {
			normalized = normalized.concat("/");
		}
		return normalized;
	}

	private void copy(ZipEntry inputEntry, ZipInputStream zipInputStream, ZipOutputStream zipOutputStream,
		byte[] buffer) throws IOException {
		ZipEntry outputEntry = new ZipEntry(inputEntry);
		outputEntry.setCompressedSize(-1L);
		zipOutputStream.putNextEntry(outputEntry);
		FileUtils.transfer(zipInputStream, zipOutputStream, buffer);
		zipOutputStream.closeEntry();
	}
}
