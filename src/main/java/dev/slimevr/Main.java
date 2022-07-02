package dev.slimevr;

import dev.slimevr.gui.Keybinding;
import dev.slimevr.gui.VRServerGUI;
import io.eiren.util.logging.LogManager;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.JavaVersion;
import org.apache.commons.lang3.SystemUtils;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;


public class Main {

	public static String VERSION = "0.2.0";

	public static VRServer vrServer;

	@SuppressWarnings("unused")
	public static void main(String[] args) {
		System.setProperty("awt.useSystemAAFontSettings", "on");
		System.setProperty("swing.aatext", "true");

		CommandLineParser parser = new DefaultParser();
		HelpFormatter formatter = new HelpFormatter();
		CommandLine cmd = null;

		Options options = new Options();

		Option noGui = new Option(
			"g",
			"no-gui",
			false,
			"disable swing gui (allow for other gui to be used)"
		);
		Option help = new Option("h", "help", false, "Show help");

		options.addOption(noGui);
		options.addOption(help);
		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			formatter.printHelp("slimevr.jar", options);
			System.exit(1);
		}

		if (cmd.hasOption("help")) {
			formatter.printHelp("slimevr.jar", options);
			System.exit(0);
		}

		File dir = new File("").getAbsoluteFile();
		try {
			LogManager.initialize(new File(dir, "logs/"), dir);
		} catch (Exception e1) {
			e1.printStackTrace();
		}

		if (!SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_11)) {
			LogManager.severe("SlimeVR start-up error! A minimum of Java 11 is required.");
			JOptionPane
				.showMessageDialog(
					null,
					"SlimeVR start-up error! A minimum of Java 11 is required.",
					"SlimeVR: Java Runtime Mismatch",
					JOptionPane.ERROR_MESSAGE
				);
			return;
		}

		try {
			new ServerSocket(6969).close();
			new ServerSocket(35903).close();
			new ServerSocket(21110).close();
		} catch (IOException e) {
			LogManager
				.severe(
					"SlimeVR start-up error! Required ports are busy. Make sure there is no other instance of SlimeVR Server running."
				);
			JOptionPane
				.showMessageDialog(
					null,
					"SlimeVR start-up error! Required ports are busy. Make sure there is no other instance of SlimeVR Server running.",
					"SlimeVR: Ports are busy",
					JOptionPane.ERROR_MESSAGE
				);
			return;
		}

		try {
			vrServer = new VRServer();
			vrServer.start();
			new Keybinding(vrServer);
			if (!cmd.hasOption("no-gui"))
				new VRServerGUI(vrServer);
		} catch (Throwable e) {
			e.printStackTrace();
			try {
				Thread.sleep(2000L);
			} catch (InterruptedException e2) {
				e.printStackTrace();
			}
			System.exit(1); // Exit in case error happened on init and window
							// not appeared, but some thread
			// started
		} finally {
			try {
				Thread.sleep(2000L);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
