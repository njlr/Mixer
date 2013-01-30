package mixer.tools;

import mixer.AddressUtils;

public strictfp final class AddressStringToNumericString {
	
	public static void main(String[] args) {
		
		if (args.length == 1) {
			
			String addressString = args[0];
			
			String numericString = AddressUtils.addressStringToNumericString(addressString);
			
			System.out.println(numericString);
		}
		else {
			
			System.out.println("Tool for converting BTC addresses into numeric form");
			System.out.println("Usage: ");
			System.out.println("0 - Address String");
		}
	}
}
