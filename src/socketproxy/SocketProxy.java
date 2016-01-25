package socketproxy;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SocketProxy extends Thread {

	private static final int PROTOCOL_GPS = 2; //0x02
	private static final int PROTOCOL_ORDINARY_MESSAGES = 129; //0x81
	private static final char[] HEXARRAY = "0123456789ABCDEF".toCharArray();

	private final Logger LOGGER = Logger.getLogger(SocketProxy.class.getName());
	private static final Properties properties = new Properties();
	private ServerSocket serverSocket = null;
	private final String[] hosts;
	private final int[] ports;
	private final boolean debug;
	private final int clientSoTimeout;

	public SocketProxy(int port, String configFile) throws IOException {
		try (InputStream input = new FileInputStream(configFile)) {
			properties.load(input);
		}
		serverSocket = new ServerSocket(port);
		serverSocket.setSoTimeout(Integer.parseInt(properties.getProperty("server.socket.timeout")));
		int numberOfClients = Integer.parseInt(properties.getProperty("number.of.clients"));
		hosts = new String[numberOfClients];
		ports = new int[numberOfClients];
		for (int i = 0; i < numberOfClients; i++) {
			hosts[i] = properties.getProperty("client.host." + i);
			ports[i] = Integer.parseInt(properties.getProperty("client.port." + i));
		}
		debug = "true".equals(properties.getProperty("debug"));
		clientSoTimeout = Integer.parseInt(properties.getProperty("client.socket.timeout"));
	}

	private byte[] readPacket(InputStream in) throws IOException {
		ByteArrayOutputStream packet = new ByteArrayOutputStream();
		byte[] header = new byte[2];
		in.read(header, 0, 2);
		packet.write(header);
		int protocol = in.read();
		if (debug) {
			System.out.println("PROTOCOL:" + protocol);
		}
		packet.write(protocol);
		byte[] length = new byte[2];
		in.read(length);
		packet.write(length);
		int l = ((int) length[0] << 8) | ((int) length[1] & 0xFF);
		byte[] serialContent = new byte[l];
		in.read(serialContent, 0, l);
		packet.write(serialContent);
		return packet.toByteArray();
	}

	@Override
	public void run() {
		while (true) {
			LOGGER.log(Level.INFO, "Waiting for client on port {0}...", serverSocket.getLocalPort());
			try (Socket socket = serverSocket.accept()) {
				LOGGER.log(Level.INFO, "Just connected to {0}", socket.getRemoteSocketAddress());
				byte[] packet;
				try (InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
					List<Socket> clients = new ArrayList<>();
					for (int i = 0; i < hosts.length; i++) {
						Socket client = new Socket(hosts[i], ports[i]);
						client.setSoTimeout(clientSoTimeout);
						clients.add(client);
					}
					while (true) {
						packet = readPacket(in);
						if (debug) {
							LOGGER.log(Level.INFO, "GPS HEX: [{0}]", bytesToHex(packet));
						}
						boolean first = true;
						for (int i = 0; i < hosts.length; i++) {
							Socket client = clients.get(i);
							client.getOutputStream().write(packet);
							LOGGER.log(Level.INFO, "Data sent to [{0}:{1}].", new Object[]{hosts[i], ports[i]});
							int protocol = packet[2];
							if (first && protocol != PROTOCOL_GPS && protocol != PROTOCOL_ORDINARY_MESSAGES) {
								byte[] responsePacket = readPacket(client.getInputStream());
								out.write(responsePacket);
								LOGGER.log(Level.INFO, "Response sent back to GPS.");
								if (debug) {
									LOGGER.log(Level.INFO, "SERVER HEX: [{0}]", bytesToHex(packet));
								}
								first = false;
							}
						}
					}
				}
			} catch (SocketTimeoutException ex) {
				LOGGER.log(Level.SEVERE, "Server socket timed out.", ex);
			} catch (IOException ex) {
				LOGGER.log(Level.SEVERE, "Server I/O exception.", ex);
			}
		}

	}

	private static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = HEXARRAY[v >>> 4];
			hexChars[j * 2 + 1] = HEXARRAY[v & 0x0F];
		}
		return new String(hexChars);
	}

	public static void main(String[] args) {
		if (args.length != 2) {
			System.err.println("Port and configuration file parameter required.");
		} else {
			int port = Integer.parseInt(args[0]);
			String config = args[1];
			SocketProxy proxy;
			try {
				proxy = new SocketProxy(port, config);
				proxy.start();
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
	}

}
