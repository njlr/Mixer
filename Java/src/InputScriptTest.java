import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletTransaction;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.core.WalletTransaction.Pool;
import com.google.common.base.Preconditions;

public strictfp final class InputScriptTest {
	
	private InputScriptTest() {
		
		super();
	}
	
	public static void main(String[] args) throws IOException, ScriptException {
		
		final File walletFile = new File("./lib/test.wallet");
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
		final NetworkParameters networkParameters = wallet.getNetworkParameters();
		
		final Transaction tx = new Transaction(networkParameters);
		
		Preconditions.checkState(wallet.keychain.size() > 0);
		
		boolean inputFound = false;
		
		for (WalletTransaction i : wallet.getWalletTransactions()) {
			
			if (i.getPool() == Pool.UNSPENT) {
				
				for (TransactionOutput j : i.getTransaction().getOutputs()) {
					
					if (j.isMine(wallet) && j.isAvailableForSpending()) {
						
						tx.addInput(j);
						tx.addOutput(j.getValue(), new ECKey().toAddress(networkParameters));
						
						inputFound = true;
						
						break;
					}
				}
			}
			
			if (inputFound) {
				
				break;
			}
		}
		
		Preconditions.checkState(inputFound);
		
		tx.signInputs(SigHash.ALL, wallet);
		
		System.out.println("Tx: ");
		System.out.println(tx);
		System.out.println();
		
		for (int i = 0; i < tx.getInputs().size(); i++) {
			
			byte[] a = tx.getInput(i).getScriptBytes();
			byte[] b = tx.computeScriptBytes(i, SigHash.ALL, wallet);
			byte[] c = tx.computeScriptBytes(i, SigHash.ALL, wallet);
			
			System.out.println(i);
			System.out.println(byte2hex(a));
			System.out.println(byte2hex(b));
			System.out.println(byte2hex(c));
			System.out.println();
		}
	}
	
    public static String byte2hex(byte[] b) {
    	
    	String hs = "";
    	String stmp = "";
 
        for (int n = 0; n < b.length; n++) {
        	
        	stmp = (java.lang.Integer.toHexString(b[n] & 0XFF));
        	
        	if (stmp.length() == 1) {
        		
        		hs = hs + "0" + stmp;
        	}
        	else {
        		
        		hs = hs + stmp;
        	}
        	
        	if (n < b.length - 1) {
        		
        		hs = hs + "";
        	}
        }
        
        return hs;
    }
}
