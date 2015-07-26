package edu.columbia.psl.cc.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

import com.google.gson.reflect.TypeToken;

import cern.colt.Arrays;
import edu.columbia.psl.cc.config.MIBConfiguration;

public class CrowdExecutor {
	
	private static List<String> problems = new ArrayList<String>();
	
	private static final Class[] parameters = new Class[]{URL.class};
	
	private static final Class[] mainParameters = new Class[]{String[].class};
	
	private static final String graphRepo = "./graphrepo";
	
	private static final TypeToken<MIBConfiguration> configToken = new TypeToken<MIBConfiguration>(){};
	
	public static void main(String[] args) throws Exception {
		File graphDir = new File(graphRepo);
		if (!graphDir.exists()) {
			graphDir.mkdir();
		}
		
		System.out.println("Problem file: " + args[0]);
		File problemFile = new File(args[0]);
		
		if (problemFile.exists()) {
			Scanner scanner = new Scanner(problemFile);
			while (scanner.hasNextLine()) {
				problems.add(scanner.nextLine());
			}
		}
		
		File binDir = new File(args[1]);
		URLClassLoader sysloader = (URLClassLoader)ClassLoader.getSystemClassLoader();
		Class sysClass = URLClassLoader.class;
		
		Method method = sysClass.getDeclaredMethod("addURL", parameters);
		method.setAccessible(true);
		method.invoke(sysloader, new Object[]{binDir.toURI().toURL()});
		
		String binClusterDir = args[1] + "/" + args[2];
		File codeJamDir = new File(binClusterDir);
		
		HashMap<String, String> executableClasses = new HashMap<String, String>();
		for (File usrDir: codeJamDir.listFiles()) {
			if (usrDir.isDirectory() && !usrDir.getName().startsWith(".") && !problems.contains(usrDir.getName())) {
				String userDirName = usrDir.getName();
				File usrRepo = new File(graphDir.getAbsolutePath() + "/" + userDirName);
				if (!usrRepo.exists()) {
					usrRepo.mkdir();
				}
				
				for (File classFile: usrDir.listFiles()) {
					String className = classFile.getName();
					String fullName = args[2] + "." + userDirName + "." + className.substring(0, className.length() - 6);
					
					Class checkClass = Class.forName(fullName);
					try {
						Method mainMethod = checkClass.getMethod("main", mainParameters);
						//System.out.println(checkClass.getName());
						executableClasses.put(checkClass.getName(), usrRepo.getAbsolutePath());
					} catch (Exception ex) {
						
					}
				}
			}
		}
		
		int success = 0;
		for (String execClass: executableClasses.keySet()) {
			String graphRepoPath = executableClasses.get(execClass);
			
			//Need to set the grah path
			MIBConfiguration config = MIBConfiguration.getInstance();
			config.setTemplateMode(false);
			config.setTestDir(graphRepoPath);
			String fileName = "./config/mib_config.json";
			GsonManager.writeJsonGeneric(config, fileName, configToken, -1);
			
			String[] command = {"/Library/Java/JavaVirtualMachines/jdk1.7.0_07.jdk/Contents/Home/bin/java", "-XX:-UseSplitVerifier", "-cp", binDir.getAbsolutePath() + "/:/Users/mikefhsu/ccws/jvm-clones/MIB/lib/*", execClass};
			System.out.println("Execute " + Arrays.toString(command));
			ProcessBuilder pBuilder = new ProcessBuilder(command);
			Process process = pBuilder.start();
			int errCode = process.waitFor();
			System.out.println("Error code: " + errCode);
			if (errCode == 0) {
				success++;
			}
			
			InputStream is = process.getInputStream();
			InputStreamReader reader = new InputStreamReader(is);
			BufferedReader br = new BufferedReader(reader);
			
			InputStream errIs = process.getErrorStream();
			InputStreamReader errReader = new InputStreamReader(errIs);
			BufferedReader errBr = new BufferedReader(errReader);
			
			String buf = "";
			while ((buf = br.readLine()) != null) {
				System.out.println(buf);
			}
			
			String errBuff = "";
			while ((errBuff = errBr.readLine()) != null) {
				System.err.println(errBuff);
			}
		}
		System.out.println("Success executions: " + success);
	}

}