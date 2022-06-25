package dev.slimevr.gui;

import com.fazecast.jSerialComm.SerialPort;
import dev.slimevr.gui.swing.EJBox;
import dev.slimevr.serial.SerialListener;
import io.eiren.util.ann.AWTThread;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;


public class WiFiWindow extends JFrame implements SerialListener {

	private static String savedSSID = "";
	private static String savedPassword = "";
	private final VRServerGUI gui;
	JTextField ssidField;
	JPasswordField passwdField;
	JTextArea log;

	public WiFiWindow(VRServerGUI gui) {
		super("WiFi Settings");
		this.gui = gui;

		getContentPane().setLayout(new BoxLayout(getContentPane(), BoxLayout.LINE_AXIS));

		this.gui.server.getSerialHandler().addListener(this);

		build();
	}

	@AWTThread
	private void build() {
		if (!this.gui.server.getSerialHandler().openSerial()) {
			JOptionPane
				.showMessageDialog(
					null,
					"无法打开串行连接。检查是否已安装驱动程序，并且没有任何设备正在使用串行端口（如Cura或VScode或其他slimeVR服务器）",
					"嗨呀 连接出错啦",
					JOptionPane.ERROR_MESSAGE
				);
		}
	}

	@Override
	@AWTThread
	public void onSerialConnected(SerialPort port) {
		Container pane = getContentPane();
		pane.add(new EJBox(BoxLayout.PAGE_AXIS) {
			{
				add(
					new JLabel(
						"跟踪器连接到 "
							+ port.getSystemPortName()
							+ " ("
							+ port.getDescriptivePortName()
							+ ")"
					)
				);
				JScrollPane scroll;
				add(
					scroll = new JScrollPane(
						log = new JTextArea(10, 20),
						ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
						ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
					)
				);
				log.setLineWrap(true);
				scroll.setAutoscrolls(true);
				add(new JLabel("输入WIFI信息:"));
				add(new EJBox(BoxLayout.LINE_AXIS) {
					{
						add(new JLabel("wifi名:"));
						add(ssidField = new JTextField(savedSSID));
					}
				});
				add(new EJBox(BoxLayout.LINE_AXIS) {
					{
						add(new JLabel("密码:"));
						passwdField = new JPasswordField(savedPassword);
						passwdField.setEchoChar('\u25cf');
						add(passwdField);
						add(new JCheckBox("显示密码") {
							{
								addMouseListener(new MouseInputAdapter() {
									@Override
									public void mouseClicked(MouseEvent e) {
										if (isSelected())
											passwdField.setEchoChar((char) 0);
										else
											passwdField.setEchoChar('\u25cf');
									}
								});
							}
						});
					}
				});
				add(new JButton("发送") {
					{
						addMouseListener(new MouseInputAdapter() {
							@Override
							public void mouseClicked(MouseEvent e) {
								savedSSID = ssidField.getText();
								savedPassword = new String(passwdField.getPassword());
								gui.server.getSerialHandler().setWifi(savedSSID, savedPassword);
							}
						});
					}
				});
			}
		});

		pack();
		setLocationRelativeTo(null);
		setVisible(true);
		java.awt.EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				toFront();
				repaint();
			}
		});
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		final WiFiWindow window = this;
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent windowEvent) {
				gui.server.getSerialHandler().closeSerial();
				dispose();
				gui.server.getSerialHandler().removeListener(window);
			}
		});
	}

	@Override
	@AWTThread
	public void onSerialDisconnected() {
		log.append("[SERVER] 端口失去连接\n");
	}

	@Override
	@AWTThread
	public void onSerialLog(String str) {
		log.append(str);
	}
}
