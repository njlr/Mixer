package mixer.tools;

import java.io.File;
import java.io.IOException;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Wallet;

public strictfp final class AddressGenerator {
	
	private AddressGenerator() {
		
		super();
	}
	
	public static void main(final String[] args) throws IOException {
		
		final ECKey key = new ECKey();
		
		if (args.length == 1) {
			
			final File walletFile = new File(args[0]);
			
			final Wallet wallet = Wallet.loadFromFile(walletFile);
			
			wallet.addKey(key);
			wallet.saveToFile(walletFile);
			
			System.out.println(key.toAddress(wallet.getNetworkParameters()));
		}
		else {
			
			System.out.println("BitcoinJ Based Address Generator");
			System.out.println("Generates a new key and adds to the wallet. ");
			System.out.println("The address of the key is given as output. ");
			System.out.println("Usage: ");
			System.out.println("0 - BitcoinJ Wallet File");
			System.out.println();
			
			System.out.println("EC Key: ");
			System.out.println(key.toStringWithPrivate());
			System.out.println();
			
			final Address addressProdNet = key.toAddress(NetworkParameters.prodNet());
			
			System.out.println("ProdNet Address for Key: ");
			System.out.println(addressProdNet.toString());
			System.out.println();
			
			final Address addressTestNet = key.toAddress(NetworkParameters.testNet());
			
			System.out.println("TestNet Address for Key: ");
			System.out.println(addressTestNet.toString());
			System.out.println();
		}
	}
}
