import java.io.File;
import java.io.IOException;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Wallet;

public strictfp final class Test {
	
	private Test() {
		
		super();
	}
	
	public static void main(String[] args) {
		
		Wallet wallet = null;
		
		final File walletFile = new File("./test.wallet");
		
		try {
			
			System.out.println("Trying to load from file " + walletFile.toString() + "... ");
			
			wallet = Wallet.loadFromFile(walletFile);
			
			System.out.println("Success! ");
			
			System.out.println("Available Addresses: ");
			
			for (ECKey i : wallet.getKeys()) {
				
				System.out.println(i.toAddress(wallet.getNetworkParameters()));
			}
		} 
		catch (IOException e) {
			
			System.out.println("Failed!");
			
			System.out.println("Creating a new wallet file... ");
			
			wallet = new Wallet(NetworkParameters.testNet());
			
			System.out.println("Adding 5 addresses... ");
			
			for (int i = 0; i < 5; i++) {
				
				ECKey key = new ECKey();
				
				wallet.addKey(key);
				
				System.out.println("Added " + key.toAddress(wallet.getNetworkParameters()).toString());
			}
			
			System.out.println("Saving to file... ");
			
			try {
				
				wallet.saveToFile(walletFile);
			} 
			catch (IOException e1) {
				
				System.out.println("Failed!");
				
				e1.printStackTrace();
			}
		}
	}
}
