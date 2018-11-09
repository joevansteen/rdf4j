/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.common.platform;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches a process, redirecting the output of that sub-process to the output of this (the parent) process.
 */
public final class ProcessLauncher {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private String commandLine;
	private String[] commandArray;

	private final File baseDir;
	private final List<OutputListener> listeners = new ArrayList<OutputListener>(1);
	private volatile Process subProcess;
	private final AtomicBoolean finished = new AtomicBoolean(false);

	private final StringBuilder out = new StringBuilder();
	private final StringBuilder err = new StringBuilder();

	/**
	 * Constructs a new ProcessLauncher with the given command line.
	 * 
	 * @param commandLine command line
	 */
	public ProcessLauncher(String commandLine) {
		this(commandLine, null);
	}

	/**
	 * Constructs a new ProcessLauncher with the given command line and base directory
	 * 
	 * @param commandLine command line
	 * @param baseDir base directory
	 */
	public ProcessLauncher(String commandLine, File baseDir) {
		this.commandLine = commandLine;
		this.baseDir = baseDir;
	}

	/**
	 * Constructs a new ProcessLauncher with the given command array.
	 * 
	 * @param commandArray command as array of strings
	 */
	public ProcessLauncher(String[] commandArray) {
		this(commandArray, null);
	}

	/**
	 * Constructs a new ProcessLauncher with the given command array and base directory.
	 * 
	 * @param commandArray command as array of strings
	 * @param baseDir base directory
	 */
	public ProcessLauncher(String[] commandArray, File baseDir) {
		this.commandArray = commandArray;
		this.baseDir = baseDir;
	}

	/**
	 * Constructs new process launcher with the given command element list.
	 * 
	 * @param commandList command list
	 */
	public ProcessLauncher(ArrayList<?> commandList) {
		this(commandList, null);
	}

	/**
	 * Constructs new process launcher with the given command element list and base directory.
	 * 
	 * @param commandList command list
	 * @param baseDir base directory
	 */
	public ProcessLauncher(ArrayList<?> commandList, File baseDir) {
		this(toStringArray(commandList), baseDir);
	}

	/**
	 * Turn a list of objects into an array of strings
	 * 
	 * @param <T>
	 * @param list list of objects
	 * @return array of strings
	 */
	private static <T> String[] toStringArray(ArrayList<T> list) {
		String[] result = new String[list.size()];
		Iterator<T> iter = list.iterator();
		int arrayIndex = 0;
		while (iter.hasNext()) {
			result[arrayIndex++] = iter.next().toString();
		}
		return result;
	}

	/**
	 * Classes implementing this interface can receive output generated by processes launched using the
	 * ProcessLauncher.
	 */
	public interface OutputListener {

		/**
		 * Send to standard output
		 * 
		 * @param output text to output
		 */
		public void standardOutput(char[] output);

		/**
		 * Send to standard error
		 * 
		 * @param output test to output
		 */
		public void errorOutput(char[] output);
	}

	/**
	 * Add a listener for output from the to-be-launched process.
	 * 
	 * @param listener output listener
	 */
	public void addOutputListener(OutputListener listener) {
		this.listeners.add(listener);
	}

	/**
	 * Fire error output event 
	 * 
	 * @param err
	 */
	private void fireErr(char[] err) {
		if (this.listeners.isEmpty()) {
			this.err.append(out);
		}
		Iterator<OutputListener> iter = this.listeners.iterator();
		while (iter.hasNext()) {
			iter.next().errorOutput(err);
		}
	}

	/**
	 * Fire standard output event
	 * 
	 * @param out
	 */
	private void fireOut(char[] out) {
		if (this.listeners.isEmpty()) {
			this.out.append(out);
		}
		Iterator<OutputListener> iter = this.listeners.iterator();
		while (iter.hasNext()) {
			iter.next().standardOutput(out);
		}
	}

	/**
	 * Get standard output, in case no listeners were registered - never returns null.
	 * 
	 * @return standard output as string
	 */
	public String getStandardOutput() {
		if (!this.listeners.isEmpty()) {
			throw new IllegalStateException(
					"Cannot get standard output, because outputlisteners have been registered.");
		}
		return this.out.toString();
	}

	/**
	 * Get error output, in case no listeners were registered - never returns null.
	 * 
	 * @return standard error as string
	 */
	public String getErrorOutput() {
		if (!this.listeners.isEmpty()) {
			throw new IllegalStateException(
					"Cannot get error output, because outputlisteners have been registered.");
		}
		return this.err.toString();
	}

	/**
	 * Get the commandline that is used to launch the process.
	 * 
	 * @return command line
	 */
	public String getCommandLine() {
		if (this.commandLine != null) {
			return this.commandLine;
		}
		else if (this.commandArray != null) {
			StringBuilder result = new StringBuilder(64);
			for (int i = 0; i < this.commandArray.length; i++) {
				if (i > 0) {
					result.append(' ');
				}
				result.append(this.commandArray[i]);
			}
			return result.toString();
		}
		else {
			return null;
		}
	}

	/**
	 * Check whether execution has finished.
	 * 
	 * @return true when finished
	 */
	public boolean hasFinished() {
		return finished.get();
	}

	/**
	 * Launches the process, and blocks until that process completes execution.
	 *
	 * @return command exit value
	 * @throws CommandNotExistsException
	 *         If the command could not be executed because it does not exist
	 */
	public int launch() throws CommandNotExistsException {
		this.err.setLength(0);
		this.out.setLength(0);
		BackgroundPrinter stdout = null;
		BackgroundPrinter stderr = null;
		try {
			Process nextSubProcess = subProcess;
			try {
				if (this.commandArray != null) {
					nextSubProcess = subProcess = Runtime.getRuntime().exec(this.commandArray, null,
							this.baseDir);
				}
				else {
					nextSubProcess = subProcess = Runtime.getRuntime().exec(this.commandLine, null,
							this.baseDir);
				}
				stdout = new BackgroundPrinter(nextSubProcess.getInputStream(), false);
				stderr = new BackgroundPrinter(nextSubProcess.getErrorStream(), true);
				stdout.start();
				stderr.start();
				// kill process and wait max 10 seconds for output to complete
				int exitValue = nextSubProcess.waitFor();
				stdout.join(10000);
				stderr.join(10000);
				if (exitValue != 0) {
					logger.info(
							"WARNING: exit value " + exitValue + " for command \"" + getCommandLine() + "\"");
				}
				return exitValue;
			}
			finally {
				try {
					subProcess = null;
					if (nextSubProcess != null) {
						nextSubProcess.destroy();
					}
				}
				finally {
					try {
						if (stdout != null) {
							stdout.close();
						}
					}
					finally {
						try {
							if (stderr != null) {
								stderr.close();
							}
						}
						finally {
							this.finished.set(true);
						}
					}
				}
			}
		}
		catch (IOException ioe) {
			// usually caused if the command does not exist at all
			throw new CommandNotExistsException("Command probably does not exist: " + ioe);
		}
		catch (Exception e) {
			logger.error("Exception while running/launching \"" + getCommandLine() + "\".", e);
		}
		return -1;
	}

	/**
	 * Tries to abort the currently running process.
	 */
	public void abort() {
		Process nextSubProcess = subProcess;
		subProcess = null;
		if (nextSubProcess != null) {
			nextSubProcess.destroy();
		}
	}

	/**
	 * Catches output from a "java.lang.Process" and writes it to either System.err or System.out.
	 */
	private class BackgroundPrinter extends Thread implements Closeable {

		private InputStream in;

		boolean isErrorOutput;

		/**
		 * Constructor
		 * 
		 * @param in inputstream
		 * @param isErrorOutput true if standard error
		 */
		public BackgroundPrinter(InputStream in, boolean isErrorOutput) {
			this.in = in;
			this.isErrorOutput = isErrorOutput;
		}

		@Override
		public void run() {
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(this.in));
				// read buffer
				char[] buf = new char[1024];
				// write data to target, until no more data is left to read
				int numberOfReadBytes;
				while ((numberOfReadBytes = reader.read(buf)) != -1) {
					char[] clearedbuf = new char[numberOfReadBytes];
					System.arraycopy(buf, 0, clearedbuf, 0, numberOfReadBytes);
					if (this.isErrorOutput) {
						fireErr(clearedbuf);
					}
					else {
						fireOut(clearedbuf);
					}
				}
				/*
				 * } catch (IOException ioe) { // ignore this: process has ended, causing IOException } catch
				 * (NullPointerException ioe) { // ignore this: there was no resulting output
				 */
			}
			catch (Exception e) {
				logger.warn("Exception while reading from stream from subprocess.", e);
			}
		}

		@Override
		public void close()
			throws IOException
		{
			try {
				this.in.close();
			}
			catch (Exception e) {
				logger.warn("Closing background stream for launched process caused exception.", e);
			}
		}
	}

	/**
	 * Exception that is thrown when a command could not be executed because it (probably) does not exist at
	 * all.
	 */
	public static class CommandNotExistsException extends RuntimeException {

		private static final long serialVersionUID = -3770613178610919742L;

		/**
		 * Construct a new exception for a command that does not exist.
		 *
		 * @param msg
		 *        The message for this exception.
		 */
		public CommandNotExistsException(String msg) {
			super(msg);
		}
	}
}
