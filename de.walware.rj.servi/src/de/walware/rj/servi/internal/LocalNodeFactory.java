/*=============================================================================#
 # Copyright (c) 2009-2016 Stephan Wahlbrink (WalWare.de) and others.
 # All rights reserved. This program and the accompanying materials
 # are made available under the terms of the Eclipse Public License v1.0
 # which accompanies this distribution, and is available at
 # http://www.eclipse.org/legal/epl-v10.html
 # 
 # Contributors:
 #     Stephan Wahlbrink - initial API and implementation
 #=============================================================================*/

package de.walware.rj.servi.internal;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.rmi.NotBoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import de.walware.ecommons.net.RMIAddress;
import de.walware.ecommons.net.RMIRegistry;

import de.walware.rj.RjException;
import de.walware.rj.RjInvalidConfigurationException;
import de.walware.rj.server.Server;
import de.walware.rj.server.ServerLogin;
import de.walware.rj.server.srvext.RJContext;
import de.walware.rj.server.srvext.ServerUtil;
import de.walware.rj.servi.pool.RServiNode;
import de.walware.rj.servi.pool.RServiNodeConfig;


public class LocalNodeFactory implements NodeFactory {
	
	
	public static final String[] CODEBASE_LIBS = new String[] {
			ServerUtil.RJ_SERVER_ID }; // RServiUtil.RJ_SERVI_ID, RServiUtil.RJ_CLIENT_ID
	
	private static void copySystemProperty(final String key, final List<String> command) {
		final String property = System.getProperty(key);
		if (property != null) {
			command.add("-D" + key + "=" + property);
		}
	}
	
	private static void copySystemPropertyPath(final String key, final List<String> command) {
		String property = System.getProperty(key);
		if (property != null) {
			if (!new File(property).isAbsolute()) {
				property = new File(System.getProperty("user.dir"), property).getPath();
			}
			command.add("-D" + key + "=" + property);
		}
	}
	
	private static List<String> createSSLPropertyArgs() {
		final List<String> args = new ArrayList<>();
		copySystemPropertyPath("javax.net.ssl.keyStore", args);
		copySystemProperty("javax.net.ssl.keyStorePassword", args);
		copySystemPropertyPath("javax.net.ssl.trustStore", args);
		copySystemProperty("javax.net.ssl.trustStorePassword", args);
		return args;
	}
	
	private static class ProcessConfig {
		final Map<String, String> addEnv = new HashMap<>();
		final List<String> command = new ArrayList<>();
		int nameCommandIdx = -1;
		String baseWd;
		String authConfig;
		String rStartupSnippet;
	}
	
	
	private final String poolId;
	private RServiNodeConfig baseConfig;
	private final RJContext context;
	private final String[] libIds;
	
	private ProcessConfig processConfig;
	
	private String errorMessage = null;
	
	private RMIRegistry nodeRegistry;
	
	private boolean verbose;
	
	private long timeoutNanos = TimeUnit.SECONDS.toNanos(30);
	
	private final List<String> sslPropertyArgs;
	
	
	public LocalNodeFactory(final String poolId,
			final RJContext context, final String[] libIds) {
		if (poolId == null) {
			throw new NullPointerException("poolId");
		}
		if (context == null) {
			throw new NullPointerException("context");
		}
		this.poolId = poolId;
		this.context = context;
		this.libIds = libIds;
		
		this.sslPropertyArgs = createSSLPropertyArgs();
	}
	
	
	@Override
	public void setRegistry(final RMIRegistry registry) {
		this.nodeRegistry = registry;
	}
	
	@Override
	public void setConfig(final RServiNodeConfig config) throws RjInvalidConfigurationException {
		final ProcessConfig p = new ProcessConfig();
		final StringBuilder sb = new StringBuilder();
		
		String javaHome = config.getJavaHome();
		if (javaHome == null || javaHome.length() == 0) {
			javaHome = System.getProperty("java.home");
		}
		p.addEnv.put("JAVA_HOME", javaHome);
		
		p.command.add(javaHome + File.separatorChar + "bin" + File.separatorChar + "java");
		
		{	p.command.add("-classpath");
			final String[] libs;
			try {
				libs = this.context.searchRJLibs(this.libIds);
			}
			catch (final RjInvalidConfigurationException e) {
				this.errorMessage = e.getMessage();
				throw e;
			}
			String cp = config.getEnvironmentVariables().get("CLASSPATH");
			if (cp != null) {
				cp = ServerUtil.concatPathVar(libs) + File.pathSeparatorChar + cp;
			}
			else {
				cp = ServerUtil.concatPathVar(libs);
			}
			p.command.add(cp);
		}
		
		String javaArgs = config.getJavaArgs();
		if (javaArgs != null && (javaArgs = javaArgs.trim()).length() > 0) {
			p.command.addAll(Utils.parseArguments(javaArgs));
		}
		else {
			javaArgs = "";
		}
		
		final String hostname = System.getProperty("java.rmi.server.hostname");
		if (hostname != null && hostname.length() > 0) {
			p.command.add("-Djava.rmi.server.hostname=" +  hostname);
		}
		if (!javaArgs.contains("-Djava.security.policy=")) {
			sb.setLength(0);
			sb.append("-Djava.security.policy=");
			sb.append(this.context.getServerPolicyFilePath());
			p.command.add(sb.toString());
		}
		if (!javaArgs.contains("-Djava.rmi.server.codebase=")) {
			final String[] libs;
			try {
				libs = this.context.searchRJLibs(CODEBASE_LIBS);
			}
			catch (final RjInvalidConfigurationException e) {
				this.errorMessage = e.getMessage();
				throw e;
			}
			sb.setLength(0);
			sb.append("-Djava.rmi.server.codebase=");
			sb.append(ServerUtil.concatCodebase(libs));
			p.command.add(sb.toString());
		}
		
		p.command.add("de.walware.rj.servi.internal.NodeController");
		
		p.nameCommandIdx = p.command.size();
		p.command.add("");
		
		String nodeArgs = config.getNodeArgs();
		if (nodeArgs != null && (nodeArgs = nodeArgs.trim()).length() > 0) {
			p.command.addAll(Utils.parseArguments(nodeArgs));
		}
		
		String rHome = config.getRHome();
		if (rHome == null || rHome.length() == 0) {
			rHome = config.getEnvironmentVariables().get("R_HOME");
			if (rHome == null || rHome.length() == 0) {
				this.errorMessage = "Missing value for R_HOME.";
				throw new RjInvalidConfigurationException(this.errorMessage);
			}
		}
		final File rHomeFile = new File(rHome);
		if (!rHomeFile.exists() || !rHomeFile.isDirectory()) {
			this.errorMessage = "Invalid value for R_HOME (directory does not exists).";
			throw new RjInvalidConfigurationException(this.errorMessage);
		}
		p.addEnv.put("R_HOME", rHome);
		
		String rArch = config.getRArch();
		if (rArch != null && rArch.length() == 0) {
			rArch = null;
		}
		boolean rArchAuto = false;
		if (rArch == null && javaHome.equals(System.getProperty("java.home"))) {
			rArch = System.getProperty("os.arch");
			if (rArch.equals("amd64")) {
				rArch = "x86_64";
			}
			else if (rArch.equals("x86")) {
				rArch = "i386";
			}
			rArchAuto = true;
		}
		if (rArch != null) {
			// validate R_ARCH
			if (Utils.IS_WINDOWS) {
				if (rArch.equals("x86_64")) {
					rArch = "x64";
				}
				if (!new File(new File(rHomeFile, "bin"), rArch).exists()) {
					rArch = null;
				}
			}
			else {
				final File execDir = new File(new File(rHomeFile, "bin"), "exec");
				if (!new File(execDir, rArch).exists()) {
					if (execDir.exists() &&
							(rArch.equals("i386") || rArch.equals("i586") || rArch.equals("i686")) ) {
						if (new File(execDir, "i686").exists()) {
							rArch = "i686";
						}
						else if (new File(execDir, "i586").exists()) {
							rArch = "i586";
						}
						else if (new File(execDir, "i386").exists()) {
							rArch = "i386";
						}
						else {
							rArch = null;
						}
					}
					else {
						rArch = null;
					}
				}
			}
			if (rArch != null) {
				p.addEnv.put("R_ARCH", '/'+rArch);
			}
			else if (!rArchAuto) {
				Utils.logInfo("Failed to validate specified architecture, value is not used.");
			}
		}
		
		if (Utils.IS_WINDOWS) {
			final String rBinDir;
			if (rArch != null) {
				rBinDir = rHome + File.separatorChar + "bin" + File.separatorChar + rArch;
			}
			else {
				rBinDir = rHome + File.separatorChar + "bin";
			}
			final String pathEnv = System.getenv("PATH");
			p.addEnv.put("PATH", (pathEnv != null) ? (rBinDir + File.pathSeparatorChar + pathEnv) : rBinDir);
		}
		else if (Utils.IS_MAC) {
			final String rBinDir = rHome + File.separatorChar + "bin";
			final String pathEnv = System.getenv("PATH");
			p.addEnv.put("PATH", (pathEnv != null) ? (rBinDir + File.pathSeparatorChar + pathEnv) : rBinDir);
			
			final String rLibDir = rHome + File.separatorChar + "lib";
			final String libPathEnv = System.getenv("DYLD_LIBRARY_PATH");
			p.addEnv.put("DYLD_LIBRARY_PATH", (libPathEnv != null) ? (rLibDir + File.pathSeparatorChar + libPathEnv) : rLibDir);
		}
		else {
			final String rBinDir = rHome + File.separatorChar + "bin";
			final String pathEnv = System.getenv("PATH");
			p.addEnv.put("PATH", (pathEnv != null) ? (rBinDir + File.pathSeparatorChar + pathEnv) : rBinDir);
			
			final String rLibDir;
			if (rArch != null) {
				rLibDir = rHome + File.separatorChar + "lib" + File.separatorChar + rArch;
			}
			else {
				rLibDir = rHome + File.separatorChar + "lib";
			}
			final String libPathEnv = System.getenv("LD_LIBRARY_PATH");
			p.addEnv.put("LD_LIBRARY_PATH", (libPathEnv != null) ? (rLibDir + File.pathSeparatorChar + libPathEnv) : rLibDir);
		}
		
		p.baseWd = config.getBaseWorkingDirectory();
		if (p.baseWd == null || p.baseWd.length() == 0) {
			p.baseWd = System.getProperty("java.io.tmpdir");
		}
		if (!testBaseDir(p.baseWd)) {
			this.errorMessage = "Invalid working directory base path.";
			throw new RjInvalidConfigurationException(this.errorMessage);
		}
		
		for (final Entry<String, String> var : config.getEnvironmentVariables().entrySet()) {
			if (!var.getKey().equals("CLASSPATH")) {
				p.addEnv.put(var.getKey(), var.getValue());
			}
		}
		
		p.authConfig = config.getEnableConsole() ? "none" : null;
		
		p.rStartupSnippet = config.getRStartupSnippet();
		
		long timeout = config.getStartStopTimeout();
		if (timeout > 0) {
			timeout = TimeUnit.MILLISECONDS.toNanos(timeout);
		}
		synchronized (this) {
			this.verbose = config.getEnableVerbose();
			this.baseConfig = config;
			this.processConfig = p;
			this.timeoutNanos = timeout;
		}
	}
	
	private boolean testBaseDir(final String path) {
		final File file = new File(path + File.separatorChar + this.poolId + "-test");
		if (file.isDirectory()) {
			return true;
		}
		if (file.mkdirs()) {
			file.delete();
			return true;
		}
		return false;
	}
	
	@Override
	public RServiNodeConfig getConfig() {
		return this.baseConfig;
	}
	
	
	@Override
	public void createNode(final NodeHandler poolObj) throws RjException {
		final long t = System.nanoTime();
		final long timeout;
		
		final ProcessConfig p;
		final RMIRegistry registry;
		synchronized (this) {
			p = this.processConfig;
			if (p == null) {
				final String message = this.errorMessage;
				throw new RjInvalidConfigurationException((message != null) ? message :
						"Missing R node configuration.");
			}
			registry = this.nodeRegistry;
			if (registry == null) {
				throw new RjInvalidConfigurationException("Missing registry configuration.");
			}
			timeout = this.timeoutNanos;
		}
		ProcessBuilder pBuilder;
		String id;
		List<String> command = null;
		try {
			synchronized (this) {
				for (int i = 0; ; i++) {
					id = this.poolId + '-' + System.currentTimeMillis();
					poolObj.dir = new File(p.baseWd + File.separatorChar + id);
					if (!poolObj.dir.exists() && poolObj.dir.mkdirs()) {
						break;
					}
					if (i >= 20) {
						throw new RjException("Failed to create working directory (parent="+p.baseWd+").");
					}
				}
			}
			command = new ArrayList<>(p.command.size() + 2);
			command.addAll(p.command);
			poolObj.address = new RMIAddress(registry.getAddress(), id);
			command.set(p.nameCommandIdx, poolObj.address.toString());
			if (this.verbose) {
				command.add("-verbose");
			}
			if (registry.getAddress().isSSL()) {
				command.addAll(p.nameCommandIdx - 1, this.sslPropertyArgs);
			}
			pBuilder = new ProcessBuilder(command);
			pBuilder.environment().remove("Path");
			pBuilder.environment().putAll(p.addEnv);
			pBuilder.directory(poolObj.dir);
			pBuilder.redirectErrorStream(true);
		}
		catch (final Exception e) {
			throw new RjException("Error preparing R node.", e);
		}
		Process process = null;
		try {
			process = pBuilder.start();
			
			for (int i = 1; i < Integer.MAX_VALUE; i++) {
				try {
					final Server server = (Server) registry.getRegistry().lookup(id);
					final ServerLogin login = server.createLogin(Server.C_RSERVI_NODECONTROL);
					final RServiNode node = (RServiNode) server.execute(Server.C_RSERVI_NODECONTROL, null, login);
					
					Utils.logInfo("New R node started (t="+((System.nanoTime()-t)/1000000)+"ms).");
					
					String line = null;
					try {
						if (p.rStartupSnippet != null && p.rStartupSnippet.length() > 0) {
							final String[] lines = p.rStartupSnippet.split("\\p{Blank}*\\r[\\n]?|\\n\\p{Blank}*"); //$NON-NLS-1$
							for (int j = 0; j < lines.length; j++) {
								line = lines[j];
								if (line.length() > 0) {
									node.runSnippet(line);
								}
							}
						}
					}
					catch (final RjException e) {
						try {
							node.shutdown();
						}
						catch (final Exception ignore) {}
						throw new RjException("Running the R startup snippet failed in line '" + line + "'.", e);
					}
					try {
						poolObj.isConsoleEnabled = node.setConsole(p.authConfig);
					}
					catch (final RjException e) {
						try {
							node.shutdown();
						}
						catch (final Exception ignore) {}
						throw e;
					}
					
					poolObj.node = node;
					poolObj.process = process;
					return;
				}
				catch (final NotBoundException e) {
					final long diff = System.nanoTime() - t;
					if (i >= 10 && timeout >= 0 && diff > timeout) {
						throw new RjException("Start of R node aborted because of timeout (t="+(diff/1000000L)+"ms).", e);
					}
				};
				
				try {
					final int exitValue = process.exitValue();
					throw new RjException("R node process stopped (exit code = "+exitValue+").");
				}
				catch (final IllegalThreadStateException ok) {}
				
				Thread.sleep(250);
			}
		}
		catch (final Exception e) {
			final StringBuilder sb = new StringBuilder("Error starting R node:");
			if (pBuilder != null) {
				sb.append("\n<COMMAND>");
				ServerUtil.prettyPrint(pBuilder.command(), sb);
				sb.append("\n</COMMAND>");
			}
			if (process != null) {
				final char[] buffer = new char[4096];
				final InputStream stdout = process.getInputStream();
				{
					sb.append("\n<STDOUT>\n");
					final InputStreamReader reader = new InputStreamReader(stdout);
					try { // read non-blocking
						int n;
						while (reader.ready() && (n = reader.read(buffer, 0, buffer.length)) >= 0) {
							sb.append(buffer, 0, n);
						}
					}
					catch (final IOException ignore) {
					}
					process.destroy();
					try {
						int n;
						while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
							sb.append(buffer, 0, n);
						}
					}
					catch (final IOException ignore) {
					}
					finally {
						if (reader != null) {
							try {
								reader.close();
							}
							catch (final IOException ignore) {}
						}
					}
					sb.append("</STDOUT>");
				}
				final File logfile = new File(poolObj.dir, "out.log");
				if (logfile.exists()) {
					sb.append("\n<LOG file=\"out.log\">\n");
					FileReader reader = null;
					try {
						reader = new FileReader(logfile);
						int n;
						while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
							sb.append(buffer, 0, n);
							if (sb.length() > 100000) {
								break;
							}
						}
					}
					catch (final IOException ignore) {
					}
					finally {
						if (reader != null) {
							try {
								reader.close();
							}
							catch (final IOException ignore) {}
						}
					}
					sb.append("</LOG>");
				}
				sb.append("\n--------");
			}
			
			Thread.interrupted();
			if (poolObj.dir.exists() && poolObj.dir.isDirectory()) {
				ServerUtil.delDir(poolObj.dir);
			}
			throw new RjException(sb.toString(), e);
		}
	}
	
	@Override
	public void stopNode(final NodeHandler poolObj) {
		final long t = System.nanoTime();
		final long timeout = this.timeoutNanos;
		
		try {
			poolObj.shutdown();
		}
		catch (final Throwable e) {
			Utils.logWarning(Messages.ShutdownNode_error_message, e);
		}
		
		final Process process = poolObj.process;
		poolObj.process = null;
		if (process != null) {
			for (int i = 0; i < Integer.MAX_VALUE; i++) {
				try {
					Thread.sleep(250);
				}
				catch (final InterruptedException e) {
				}
				try {
					process.exitValue();
					break;
				}
				catch (final IllegalThreadStateException e) {
					final long diff = System.nanoTime() - t;
					if (i >= 10 && timeout >= 0 && diff > timeout) {
						process.destroy();
						Utils.logWarning("Killed RServi node '" + poolObj.getAddress().getName() + "'.");
						break;
					}
					continue;
				}
			}
		}
		
		if (!this.verbose && poolObj.dir != null
				&& poolObj.dir.exists() && poolObj.dir.isDirectory() ) {
			for (int i = 0; i < 20; i++) {
				try {
					Thread.sleep(250);
				}
				catch (final InterruptedException e) {
				}
				
				if (!poolObj.dir.exists() || ServerUtil.delDir(poolObj.dir)) {
					return;
				}
			}
			Utils.logWarning("Failed to delete the RServi node working directory '" + poolObj.dir.toString() + "'.");
		}
	}
	
}
