import mixer.AddressUtils;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.WrongNetworkException;
import com.google.common.base.Preconditions;

public class Test {
	
	private Test() {
		
		super();
	}
	
	public static void main(final String[] args) throws WrongNetworkException, AddressFormatException {
		
		final int tests = 999999;
		
		final NetworkParameters networkParameters = NetworkParameters.testNet();
		
		System.out.println("Running " + tests + " tests... ");
		
		for (int i = 0; i < tests; i++) {
			
			final Address address = new ECKey().toAddress(networkParameters);
			
			final String a = address.toString();
			final String b = AddressUtils.numericStringToAddressString(AddressUtils.addressStringToNumericString(a));
			
			Preconditions.checkState(a.equals(b), "Failed after " + i + " tests on " + address.toString() + ". ");
		}
		
		System.out.println("Passed " + tests + " tests. ");
	}
}
