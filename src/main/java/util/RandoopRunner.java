/**
 * 
 */
package util;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

import dynslicer.Main;

/**
 * @author schaef
 *
 */
public class RandoopRunner extends AbstractRunner{

	/**
	 * Runs Randoop for all classes in 'classListFile' using 'classPath'. 
	 * Resulting test cases are put in 'testDir'. Test generation is cut
	 * off after 'timeSec' seconds.
	 * @param classPath
	 * @param classListFile
	 * @param testSrcDir directory where to write the test source files.
	 * @param timeSec max time spent on test case generation.
	 * @param outputLimit max number of tests generated.
	 */
	public void run(final String classPath, final File classListFile, final File testSrcDir, final int timeSec, final int outputLimit) {
		System.out.println("Running Randoop.");
		
		List<String> cmd = new LinkedList<String>();
		cmd.add("java");
		cmd.add("-ea");
		cmd.add("-classpath");
		cmd.add(classPath+File.pathSeparator+Main.basePath+"lib/randoop.jar");
		cmd.add("randoop.main.Main");
		cmd.add("gentests");
		cmd.add("--classlist="+classListFile.getAbsolutePath());
		cmd.add("--timelimit="+timeSec);		
		if (outputLimit>0) {
			cmd.add("--outputlimit="+outputLimit);
		}
		cmd.add("--junit-reflection-allowed=false");
		cmd.add("--silently-ignore-bad-class-names=true");
		cmd.add("--unchecked-exception=ERROR");
		cmd.add("--no-regression-tests=true");
		cmd.add("--npe-on-null-input=ERROR");
		cmd.add("--npe-on-non-null-input=ERROR");
		cmd.add("--junit-output-dir="+testSrcDir.getAbsolutePath());			
		execute(cmd);
		
		System.out.println("Compiling test cases.");	
		cmd = new LinkedList<String>();
		cmd.add("javac");
		cmd.add("-g");
		cmd.add("-classpath");
		cmd.add(classPath+File.pathSeparator+Main.basePath+"lib/junit.jar");
		

	}
}
