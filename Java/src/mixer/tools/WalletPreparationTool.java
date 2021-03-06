package mixer.tools;

import java.io.File;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import com.google.bitcoin.core.BlockChain;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Wallet.SendResult;
import com.google.bitcoin.discovery.DnsDiscovery;
import com.google.bitcoin.discovery.IrcDiscovery;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BoundedOverheadBlockStore;

public strictfp final class WalletPreparationTool {
	
	private WalletPreparationTool() {
		
		super();
	}
	
	public static void main(final String[] args) throws Exception {
		
		if (args.length != 3) {
			
			System.out.println("Tool for preparing a wallet for mixing");
			System.out.println("Usage: ");
			System.out.println("0 - Path to the wallet file");
			System.out.println("1 - Path to the blockchain");
			System.out.println("2 - Amount of BTC to be mixed");
			
			return;
		}
		
		// Set up
		final File walletFile = new File(args[0]);
		final File blockchainFile = new File(args[1]);
		
		final BigInteger amount = Utils.toNanoCoins(args[2]);
		
		System.out.println("Preparing " + walletFile.toString() + " for a " + amount.toString() + " nanocoin mix. ");
		System.out.println();
		
		// Load the wallet
		System.out.println("Loading the wallet... ");
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
        wallet.autosaveToFile(walletFile, 200, TimeUnit.MILLISECONDS, null);
		
		System.out.println("Wallet loaded. ");
		System.out.println();
		
		// Load the block-chain
		System.out.println("Loading the block-chain... ");
		
		final BlockStore blockStore = new BoundedOverheadBlockStore(wallet.getNetworkParameters(), blockchainFile);
		final BlockChain blockChain = new BlockChain(wallet.getNetworkParameters(), wallet, blockStore);
		
		System.out.println("Blockchain loaded. ");
		System.out.println();
		
		// Connect to the network
		System.out.println("Creating the peer... ");
		
		final PeerGroup peerGroup = new PeerGroup(wallet.getNetworkParameters(), blockChain);
		
		peerGroup.setUserAgent("WalletPreparationTool", "1.0");
        
        if (wallet.getNetworkParameters().equals(NetworkParameters.prodNet())) {
            
        	peerGroup.addPeerDiscovery(new DnsDiscovery(wallet.getNetworkParameters()));
        }
        else if (wallet.getNetworkParameters().equals(NetworkParameters.testNet())) {
        	
        	peerGroup.addPeerDiscovery(new IrcDiscovery("#bitcoinTEST3"));
		}
        else {
        	
            throw new RuntimeException("Unreachable.");
        }
        
        peerGroup.addWallet(wallet);
        peerGroup.setMinBroadcastConnections(1);
        
		peerGroup.startAndWait();
		
		System.out.println("Peer ready. ");
		System.out.println();
		
		//Create a new key
		final ECKey key = new ECKey();
		
		System.out.println("Created a new key: ");
		System.out.println(key.toStringWithPrivate());
		System.out.println();
		
		// Save it to the wallet
		System.out.println("Adding the key to the wallet... ");
		
		wallet.addKey(key);
		
		System.out.println("Key added to wallet. ");
		System.out.println();
		
		// Create the transaction
		System.out.println("Sending the coins... ");
		
		final SendResult sendResult = wallet.sendCoins(peerGroup, key.toAddress(wallet.getNetworkParameters()), amount);
		
		final Transaction tx = sendResult.broadcastComplete.get();
		
		System.out.println("The transaction: ");
		System.out.println(tx.toString());
		System.out.println();
		
		// Pack up
		System.out.println("Packing up... ");
		
        wallet.saveToFile(walletFile);
        
        blockStore.close();
        
        peerGroup.stopAndWait();
        
        System.out.println("Done. ");
	}
}
