package mixer.tools;

import java.io.File;
import java.math.BigInteger;

import com.google.bitcoin.core.BlockChain;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.discovery.DnsDiscovery;
import com.google.bitcoin.discovery.IrcDiscovery;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BoundedOverheadBlockStore;

public strictfp final class WalletPreparationTool {
	
	private WalletPreparationTool() {
		
		super();
	}
	
	public static void main(String[] args) throws Exception {
		
		/*
		 * 1. Get the amount required, load the wallet-
		 * 2. Generate a new address-
		 * 3. Add it to the wallet and save-
		 * 4. Generate a new transaction from the wallet to the new address
		 * 5. Sync
		 */
		
		if (args.length != 3) {
			
			System.out.println("Tool for preparing a wallet for mixing");
			System.out.println("Usage: ");
			System.out.println("0 - Path to the wallet file");
			System.out.println("1 - Path to the blockchain");
			System.out.println("2 - Amount to be mixed, in BTC cents");
			
			return;
		}
		
		// Set up
		final File walletFile = new File(args[0]);
		final File blockchainFile = new File(args[1]);
		
		final BigInteger amount = Utils.toNanoCoins(0, Integer.parseInt(args[2]));
		
		System.out.println("Preparing " + walletFile.toString() + " for a " + amount.toString() + " nanocoin mix. ");
		
		// Load the wallet
		System.out.println("Loading the wallet... ");
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
		System.out.println("Wallet loaded. ");
		
		// Load the blockchain
		System.out.println("Loading the blockchain... ");
		
		final BlockStore blockStore = new BoundedOverheadBlockStore(wallet.getNetworkParameters(), blockchainFile);
		final BlockChain blockChain = new BlockChain(wallet.getNetworkParameters(), wallet, blockStore);
		
		System.out.println("Blockchain loaded. ");
		
		// Connect to the network
		System.out.println("Starting the peer... ");
		
		final PeerGroup peerGroup = new PeerGroup(wallet.getNetworkParameters(), blockChain);
		
        peerGroup.addWallet(wallet);
        
        if (wallet.getNetworkParameters().equals(NetworkParameters.prodNet())) {
            
        	peerGroup.addPeerDiscovery(new DnsDiscovery(wallet.getNetworkParameters()));
        }
        else if (wallet.getNetworkParameters().equals(NetworkParameters.testNet())) {
        	
        	peerGroup.addPeerDiscovery(new IrcDiscovery("#bitcoinTEST3"));
		}
        else {
        	
            throw new RuntimeException("Unreachable.");
        }
        
		peerGroup.startAndWait();
		peerGroup.setMinBroadcastConnections(1);
		
		System.out.println("Peer ready. ");
		
		//Create a new key
		final ECKey key = new ECKey();
		
		System.out.println("Created a new key: ");
		System.out.println(key.toStringWithPrivate());
		
		// Save it to the wallet
		System.out.println("Adding the key to the wallet... ");
		
		wallet.addKey(key);
		wallet.saveToFile(walletFile);
		
		// Create the transaction
		System.out.println("Creating the transaction... ");
		
		final Transaction transaction = wallet.createSend(key.toAddress(wallet.getNetworkParameters()), amount);
		
		System.out.println("Transaction ready. ");
		
		// Broadcasting
		System.out.println("Broadcasting... ");
		
		peerGroup.broadcastTransaction(transaction).get();
		
		System.out.println("Broadcast complete. ");
		
		// Pack up
		System.out.println("Packing up... ");
		
        wallet.saveToFile(walletFile);
        
        blockStore.close();
        
        peerGroup.stopAndWait();
        
        System.out.println("Success! ");
	}
}
