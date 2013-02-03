package mixer.tools;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;

public strictfp final class AddressGenerator {
	
	private AddressGenerator() {
		
		super();
	}
	
	public static void main(String[] args) {
		
		System.out.println("BitcoinJ Based Address Generator");
		System.out.println();
		
		ECKey key = new ECKey();
		
		System.out.println("EC Key: ");
		System.out.println(key.toStringWithPrivate());
		System.out.println();
		
		Address addressProdNet = key.toAddress(NetworkParameters.prodNet());
		
		System.out.println("ProdNet Address for Key: ");
		System.out.println(addressProdNet.toString());
		System.out.println();
		
		Address addressTestNet = key.toAddress(NetworkParameters.testNet());
		
		System.out.println("TestNet Address for Key: ");
		System.out.println(addressTestNet.toString());
		System.out.println();
	}
}
