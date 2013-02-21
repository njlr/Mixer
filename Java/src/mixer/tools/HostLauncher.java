package mixer.tools;

import java.math.BigInteger;

import mixer.protocol.Host;

import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;
import com.google.common.base.Preconditions;

public strictfp final class HostLauncher {
	
	private HostLauncher() {
		
		super();
	}
	
	public static void main(final String[] args) {
		
		if (args.length != 4) {
			
			System.out.println("Mixing Host Launcher");
			System.out.println("Usage: ");
			System.out.println("0 - Network Parameters <PROD|TEST>");
			System.out.println("1 - Amount of BTC");
			System.out.println("2 - Port");
			System.out.println("3 - Player Count");
			
			return;
		}
		
		NetworkParameters networkParameters = null;
		
		if (args[0].equals("PROD")) {
			
			networkParameters = NetworkParameters.prodNet();
		}
		else if (args[0].equals("TEST")) {
			
			networkParameters = NetworkParameters.testNet();
		}
		else {
			
			Preconditions.checkArgument(false, "Network Parameters must be either PROD or TEST. ");
		}
		
		final BigInteger amount = Utils.toNanoCoins(args[1]);
		
		final int port = Integer.parseInt(args[2]);
		final int playerCount = Integer.parseInt(args[3]);
		
		final Host host = new Host(networkParameters, amount, port, playerCount);
		
		host.startAndWait();
	}
}
