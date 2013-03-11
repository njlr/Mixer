package mixer.tools;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Set;

import mixer.protocol.Client;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.WrongNetworkException;

public strictfp final class ClientLauncher {
	
	private ClientLauncher() {
		
		super();
	}
	
	public static void main(final String[] args) throws IOException, WrongNetworkException, AddressFormatException {
		
		if (args.length < 5) {
			
			System.out.println("Mixing Client Launcher");
			System.out.println("Usage: ");
			System.out.println("0 - BitcoinJ wallet file");
			System.out.println("1 - Amount of BTC");
			System.out.println("2 - Host name");
			System.out.println("3 - Host port");
			System.out.println("TargetAddresses + ");
			
			return;
		}
		
		final File walletFile = new File(args[0]);
		
		final BigInteger amount = Utils.toNanoCoins(args[1]);
		
		final String hostName = args[2];
		final int hostPort = Integer.parseInt(args[3]);
		
		final SocketAddress hostAddress = new InetSocketAddress(InetAddress.getByName(hostName), hostPort);
		
		final Set<Address> targetAddresses = new HashSet<Address>();
		
		for (int i = 4; i < args.length; i++) {
			
			targetAddresses.add(new Address(Address.getParametersFromAddress(args[i]), args[i]));
		}
		
		final Client client = new Client(walletFile, amount, hostAddress, targetAddresses);
		
		client.startAndWait();
	}
}
