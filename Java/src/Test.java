import java.io.File;
import java.io.IOException;

import mixer.MixerUtils;

import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;


public class Test {
	
	public static void main(String[] args) throws IOException {
		
		final File walletFile = new File("./lib/test.wallet");
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
		TransactionOutput o = MixerUtils.getClosestOutput(wallet, Utils.toNanoCoins(20, 0));
		
		System.out.println(o);
	}
}
