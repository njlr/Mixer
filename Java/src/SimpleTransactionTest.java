import java.io.File;
import java.math.BigInteger;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletTransaction;
import com.google.bitcoin.core.WalletTransaction.Pool;
import com.google.common.base.Preconditions;

public strictfp final class SimpleTransactionTest {
	
	private SimpleTransactionTest() {
		
		super();
	}
	
	public static void main(String[] args) throws Exception {
		
		// Load our test wallet
		final File walletFile = new File("./lib/test.wallet");
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
		Preconditions.checkState(!wallet.keychain.isEmpty());
		
		// Store its network type
		final NetworkParameters networkParameters = wallet.getNetworkParameters();
		
		// Create a new tx
		final Transaction tx = new Transaction(networkParameters);
		
		// Create a recipient
		final ECKey recipient = new ECKey(new BigInteger("1234567")); // Just a test so deterministic key selection is fine. 
		
		final BigInteger amount = Utils.toNanoCoins(0, 10);
		
		// Look for an unspent output... 
		boolean foundInput = false;
		
		for (WalletTransaction i : wallet.getWalletTransactions()) {
			
			if (i.getPool() == Pool.UNSPENT) {
				
				for (TransactionOutput j : i.getTransaction().getOutputs()) {
					
					if (j.isMine(wallet) && j.isAvailableForSpending() && j.getValue().compareTo(amount) >= 0) {
						
						// Use as input
						tx.addInput(j);
						
						// Send amount it to the recipient
						tx.addOutput(amount, recipient.toAddress(networkParameters));
						
						// Take our change
						tx.addOutput(j.getValue().subtract(amount), wallet.keychain.get(0).toAddress(networkParameters));
						
						// Stop looking
						foundInput = true;
						
						break;
					}
				}
			}
			
			if (foundInput) {
				
				break;
			}
		}
		
		if (foundInput) {
			
			// Sign the tx
			//tx.signInputs(SigHash.ALL, wallet);
			
			for (int i = 0; i < tx.getInputs().size(); i++) {
				
				tx.getInput(i).setScriptBytes(tx.computeScriptBytes(i, SigHash.ALL, wallet));
			}
			
			// Commit
			//wallet.commitTx(tx);
			//wallet.saveToFile(walletFile);
			
			// Show the tx to the user
			System.out.println(tx);
		}
		else {
			
			System.out.println("No viable input found. ");
		}
	}
}