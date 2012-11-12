package mixer;

import java.io.UnsupportedEncodingException;

public class Test {

	public static void main(String[] args) throws UnsupportedEncodingException {
			
		String addressString = "37muSN5ZrukVTvyVh3mT5Zc5ew9L9CBare";
		
		System.out.println(addressString);
		
		String numericString = Utils.addressStringToNumericString(addressString);
		
		System.out.println(numericString);
		
		String addressString_ = Utils.numericStringToAddressString(numericString);
		
		System.out.println(addressString_);
	}
}
