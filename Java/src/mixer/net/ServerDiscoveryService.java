package mixer.net;

import java.net.SocketAddress;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;


public strictfp interface ServerDiscoveryService extends Service {
	
	ListenableFuture<SocketAddress> getServerSocketAddress();
}
