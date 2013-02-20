package mixer.tools;

import mixer.AddressUtils;

public strictfp final class AddressStringToNumericString {
	
	private AddressStringToNumericString() {
		
		super();
	}
	
	public static void main(final String[] args) {
		
		if (args.length == 0) {
			
			System.out.println("Tool for converting BTC addresses into numeric form");
			System.out.println("Usage: ");
			System.out.println("Address String + ");
			
			return;
		}
		
		for (final String i : args) {
			
			System.out.print(AddressUtils.addressStringToNumericString(i) + " ");
		}
		
		System.out.println();
	}
}
