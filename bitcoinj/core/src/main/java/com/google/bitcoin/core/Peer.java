/**
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.core;

import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.utils.EventListenerInvoker;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.*;
import org.jboss.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A Peer handles the high level communication with a Bitcoin node.
 *
 * <p>{@link Peer#getHandler()} is part of a Netty Pipeline with a Bitcoin serializer downstream of it.
 */
public class Peer {
    interface PeerLifecycleListener {
        /** Called when the peer is connected */
        public void onPeerConnected(Peer peer);
        /** Called when the peer is disconnected */
        public void onPeerDisconnected(Peer peer);
    }

    private static final Logger log = LoggerFactory.getLogger(Peer.class);

    private final NetworkParameters params;
    private final AbstractBlockChain blockChain;
    private PeerAddress address;
    // TODO: Make the types here explicit and remove synchronization on adders/removers.
    private List<PeerEventListener> eventListeners;
    private List<PeerLifecycleListener> lifecycleListeners;
    // Whether to try and download blocks and transactions from this peer. Set to false by PeerGroup if not the
    // primary peer. This is to avoid redundant work and concurrency problems with downloading the same chain
    // in parallel.
    private boolean downloadData = false;
    // The version data to announce to the other side of the connections we make: useful for setting our "user agent"
    // equivalent and other things.
    private VersionMessage versionMessage;
    // How many block messages the peer has announced to us. Peers only announce blocks that attach to their best chain
    // so we can use this to calculate the height of the peers chain, by adding it to the initial height in the version
    // message. This method can go wrong if the peer re-orgs onto a shorter (but harder) chain, however, this is rare.
    private int blocksAnnounced;
    // A class that tracks recent transactions that have been broadcast across the network, counts how many
    // peers announced them and updates the transaction confidence data. It is passed to each Peer.
    // TODO: Make this final and unsynchronized.
    private MemoryPool memoryPool;
    // Each wallet added to the peer will be notified of downloaded transaction data.
    private CopyOnWriteArrayList<Wallet> wallets;
    // A time before which we only download block headers, after that point we download block bodies.
    private long fastCatchupTimeSecs;
    // Whether we are currently downloading headers only or block bodies. Starts at true. If the fast catchup time is
    // set AND our best block is before that date, switch to false until block headers beyond that point have been
    // received at which point it gets set to true again. This isn't relevant unless downloadData is true.
    private boolean downloadBlockBodies = true;
    // Whether to request filtered blocks instead of full blocks if the protocol version allows for them.
    private boolean useFilteredBlocks = false;
    // The last filtered block we received, we're waiting to fill it out with transactions.
    private FilteredBlock currentFilteredBlock = null;
    // Keeps track of things we requested internally with getdata but didn't receive yet, so we can avoid re-requests.
    // It's not quite the same as getDataFutures, as this is used only for getdatas done as part of downloading
    // the chain and so is lighter weight (we just keep a bunch of hashes not futures).
    //
    // It is important to avoid a nasty edge case where we can end up with parallel chain downloads proceeding
    // simultaneously if we were to receive a newly solved block whilst parts of the chain are streaming to us.
    private HashSet<Sha256Hash> pendingBlockDownloads = new HashSet<Sha256Hash>();
    // The lowest version number we're willing to accept. Lower than this will result in an immediate disconnect.
    private int minProtocolVersion = Pong.MIN_PROTOCOL_VERSION;
    // When an API user explicitly requests a block or transaction from a peer, the InventoryItem is put here
    // whilst waiting for the response. Synchronized on itself. Is not used for downloads Peer generates itself.
    private static class GetDataRequest {
        Sha256Hash hash;
        SettableFuture future;
        // If the peer does not support the notfound message, we'll use ping/pong messages to simulate it. This is
        // a nasty hack that relies on the fact that bitcoin-qt is single threaded and processes messages in order.
        // The nonce field records which pong should clear this request as "not found".
        long nonce;
    }
    private final CopyOnWriteArrayList<GetDataRequest> getDataFutures;

    // Outstanding pings against this peer and how long the last one took to complete.
    private final CopyOnWriteArrayList<PendingPing> pendingPings;
    private long[] lastPingTimes;
    private static final int PING_MOVING_AVERAGE_WINDOW = 20;

    private Channel channel;
    private VersionMessage peerVersionMessage;
    private boolean isAcked;
    private PeerHandler handler;

    /**
     * Construct a peer that reads/writes from the given block chain.
     */
    public Peer(NetworkParameters params, AbstractBlockChain chain, VersionMessage ver) {
        this.params = Preconditions.checkNotNull(params);
        this.versionMessage = Preconditions.checkNotNull(ver);
        this.blockChain = chain;  // Allowed to be null.
        this.downloadData = chain != null;
        this.getDataFutures = new CopyOnWriteArrayList<GetDataRequest>();
        this.eventListeners = new CopyOnWriteArrayList<PeerEventListener>();
        this.lifecycleListeners = new CopyOnWriteArrayList<PeerLifecycleListener>();
        this.fastCatchupTimeSecs = params.genesisBlock.getTimeSeconds();
        this.isAcked = false;
        this.handler = new PeerHandler();
        this.pendingPings = new CopyOnWriteArrayList<PendingPing>();
        this.lastPingTimes = null;
        this.wallets = new CopyOnWriteArrayList<Wallet>();
    }

    /**
     * Construct a peer that reads/writes from the given chain. Automatically creates a VersionMessage for you from the
     * given software name/version strings, which should be something like "MySimpleTool", "1.0" and which will tell the
     * remote node to relay transaction inv messages before it has received a filter.
     */
    public Peer(NetworkParameters params, AbstractBlockChain blockChain, String thisSoftwareName, String thisSoftwareVersion) {
        this(params, blockChain, new VersionMessage(params, blockChain.getBestChainHeight(), true));
        this.versionMessage.appendToSubVer(thisSoftwareName, thisSoftwareVersion, null);
    }

    public synchronized void addEventListener(PeerEventListener listener) {
        eventListeners.add(listener);
    }

    public synchronized boolean removeEventListener(PeerEventListener listener) {
        return eventListeners.remove(listener);
    }

    synchronized void addLifecycleListener(PeerLifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    synchronized boolean removeLifecycleListener(PeerLifecycleListener listener) {
        return lifecycleListeners.remove(listener);
    }

    /**
     * Tells the peer to insert received transactions/transaction announcements into the given {@link MemoryPool}.
     * This is normally done for you by the {@link PeerGroup} so you don't have to think about it. Transactions stored
     * in a memory pool will have their confidence levels updated when a peer announces it, to reflect the greater
     * likelyhood that the transaction is valid.
     *
     * @param pool A new pool or null to unlink.
     */
    public synchronized void setMemoryPool(MemoryPool pool) {
        memoryPool = pool;
    }

    @Override
    public synchronized String toString() {
        if (address == null) {
            // User-provided NetworkConnection object.
            return "Peer()";
        } else {
            return "Peer(" + address.getAddr() + ":" + address.getPort() + ")";
        }
    }

    private void notifyDisconnect() {
        for (PeerLifecycleListener listener : lifecycleListeners) {
            synchronized (listener) {
                listener.onPeerDisconnected(Peer.this);
            }
        }
    }

    class PeerHandler extends SimpleChannelHandler {
        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            super.channelClosed(ctx, e);
            notifyDisconnect();
        }

        @Override
        public void connectRequested(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            synchronized (Peer.this) {
                address = new PeerAddress((InetSocketAddress)e.getValue());
            }
            channel = e.getChannel();
            super.connectRequested(ctx, e);
        }

        /** Catch any exceptions, logging them and then closing the channel. */ 
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
            String s;
            synchronized (Peer.this) {
                s = address == null ? "?" : address.toString();
            }
            if (e.getCause() instanceof ConnectException || e.getCause() instanceof IOException) {
                // Short message for network errors
                log.info(s + " - " + e.getCause().getMessage());
            } else {
                log.warn(s + " - ", e.getCause());
            }

            e.getChannel().close();
        }

        /** Handle incoming Bitcoin messages */
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            Message m = (Message)e.getMessage();

            // Allow event listeners to filter the message stream. Listeners are allowed to drop messages by
            // returning null.
            synchronized (Peer.this) {
                for (PeerEventListener listener : eventListeners) {
                    synchronized (listener) {
                        m = listener.onPreMessageReceived(Peer.this, m);
                        if (m == null) break;
                    }
                }
            }

            if (m == null) return;
            
            if (currentFilteredBlock != null && !(m instanceof Transaction)) {
                processFilteredBlock(currentFilteredBlock);
                currentFilteredBlock = null;
            }

            if (m instanceof NotFoundMessage) {
                // This is sent to us when we did a getdata on some transactions that aren't in the peers memory pool.
                // Because NotFoundMessage is a subclass of InventoryMessage, the test for it must come before the next.
                processNotFoundMessage((NotFoundMessage) m);
            } else if (m instanceof InventoryMessage) {
                processInv((InventoryMessage) m);
            } else if (m instanceof Block) {
                processBlock((Block) m);
            } else if (m instanceof FilteredBlock) {
                // Filtered blocks come before the data that they refer to, so stash it here and then fill it out as
                // messages stream in. We'll call processFilteredBlock when a non-tx message arrives (eg, another
                // FilteredBlock) or when a tx that isn't needed by that block is found. A ping message is sent after
                // a getblocks, to force the non-tx message path.
                currentFilteredBlock = (FilteredBlock)m;
            } else if (m instanceof Transaction) {
                processTransaction((Transaction) m);
            } else if (m instanceof GetDataMessage) {
                processGetData((GetDataMessage) m);
            } else if (m instanceof AddressMessage) {
                // We don't care about addresses of the network right now. But in future,
                // we should save them in the wallet so we don't put too much load on the seed nodes and can
                // properly explore the network.
            } else if (m instanceof HeadersMessage) {
                processHeaders((HeadersMessage) m);
            } else if (m instanceof AlertMessage) {
                processAlert((AlertMessage) m);
            } else if (m instanceof VersionMessage) {
                synchronized (Peer.this) {
                    peerVersionMessage = (VersionMessage)m;
                }
                EventListenerInvoker.invoke(lifecycleListeners, new EventListenerInvoker<PeerLifecycleListener>() {
                    @Override
                    public void invoke(PeerLifecycleListener listener) {
                        listener.onPeerConnected(Peer.this);
                    }
                });
                if (peerVersionMessage.clientVersion < minProtocolVersion) {
                    log.warn("Connected to a peer speaking protocol version {} but need {}, closing",
                            peerVersionMessage.clientVersion, minProtocolVersion);
                    e.getChannel().close();
                }
            } else if (m instanceof VersionAck) {
                synchronized (Peer.this) {
                    if (peerVersionMessage == null) {
                        throw new ProtocolException("got a version ack before version");
                    }
                    if (isAcked) {
                        throw new ProtocolException("got more than one version ack");
                    }
                    isAcked = true;
                }
            } else if (m instanceof Ping) {
                if (((Ping) m).hasNonce())
                    sendMessage(new Pong(((Ping) m).getNonce()));
            } else if (m instanceof Pong) {
                processPong((Pong)m);
            } else {
                log.warn("Received unhandled message: {}", m);
            }
        }

        public Peer getPeer() {
            return Peer.this;
        }
    }

    private void processNotFoundMessage(NotFoundMessage m) {
        // This is received when we previously did a getdata but the peer couldn't find what we requested in it's
        // memory pool. Typically, because we are downloading dependencies of a relevant transaction and reached
        // the bottom of the dependency tree (where the unconfirmed transactions connect to transactions that are
        // in the chain).
        //
        // We go through and cancel the pending getdata futures for the items we were told weren't found.
        for (ListIterator<GetDataRequest> it = getDataFutures.listIterator(); it.hasNext();) {
            GetDataRequest req = it.next();
            for (InventoryItem item : m.getItems()) {
                if (item.hash.equals(req.hash)) {
                    req.future.cancel(true);
                    getDataFutures.remove(req);
                    break;
                }
            }
        }
    }

    private synchronized void processAlert(AlertMessage m) {
        try {
            if (m.isSignatureValid()) {
                log.info("Received alert from peer {}: {}", toString(), m.getStatusBar());
            } else {
                log.warn("Received alert with invalid signature from peer {}: {}", toString(), m.getStatusBar());
            }
        } catch (Throwable t) {
            // Signature checking can FAIL on Android platforms before Gingerbread apparently due to bugs in their
            // BigInteger implementations! See issue 160 for discussion. As alerts are just optional and not that
            // useful, we just swallow the error here.
            log.error("Failed to check signature: bug in platform libraries?", t);
        }
    }

    /** Returns the Netty Pipeline stage handling the high level Bitcoin protocol. */
    public PeerHandler getHandler() {
        return handler;
    }

    private synchronized void processHeaders(HeadersMessage m) throws IOException, ProtocolException {
        // Runs in network loop thread for this peer.
        //
        // This method can run if a peer just randomly sends us a "headers" message (should never happen), or more
        // likely when we've requested them as part of chain download using fast catchup. We need to add each block to
        // the chain if it pre-dates the fast catchup time. If we go past it, we can stop processing the headers and
        // request the full blocks from that point on instead.
        Preconditions.checkState(!downloadBlockBodies, toString());

        try {
            for (int i = 0; i < m.getBlockHeaders().size(); i++) {
                Block header = m.getBlockHeaders().get(i);
                if (header.getTimeSeconds() < fastCatchupTimeSecs) {
                    if (blockChain.add(header)) {
                        // The block was successfully linked into the chain. Notify the user of our progress.
                        invokeOnBlocksDownloaded(header);
                    } else {
                        // This block is unconnected - we don't know how to get from it back to the genesis block yet.
                        // That must mean that the peer is buggy or malicious because we specifically requested for
                        // headers that are part of the best chain.
                        throw new ProtocolException("Got unconnected header from peer: " + header.getHashAsString());
                    }
                } else {
                    log.info("Passed the fast catchup time, discarding {} headers and requesting full blocks",
                            m.getBlockHeaders().size() - i);
                    downloadBlockBodies = true;
                    lastGetBlocksBegin = Sha256Hash.ZERO_HASH;  // Prevent this request being seen as a duplicate.
                    blockChainDownload(Sha256Hash.ZERO_HASH);
                    return;
                }
            }
            // We added all headers in the message to the chain. Request some more if we got up to the limit, otherwise
            // we are at the end of the chain.
            if (m.getBlockHeaders().size() >= HeadersMessage.MAX_HEADERS)
                blockChainDownload(Sha256Hash.ZERO_HASH);
        } catch (VerificationException e) {
            log.warn("Block header verification failed", e);
        } catch (PrunedException e) {
            // Unreachable when in SPV mode.
            throw new RuntimeException(e);
        }
    }

    private synchronized void processGetData(GetDataMessage getdata) throws IOException {
        log.info("{}: Received getdata message: {}", address, getdata.toString());
        ArrayList<Message> items = new ArrayList<Message>();
        for (PeerEventListener listener : eventListeners) {
            synchronized (listener) {
                List<Message> listenerItems = listener.getData(this, getdata);
                if (listenerItems == null) continue;
                items.addAll(listenerItems);
            }
        }
        if (items.size() == 0) {
            return;
        }
        log.info("{}: Sending {} items gathered from listeners to peer", address, items.size());
        for (Message item : items) {
            sendMessage(item);
        }
    }

    private synchronized void processTransaction(Transaction tx) throws VerificationException, IOException {
        log.debug("{}: Received tx {}", address, tx.getHashAsString());
        if (memoryPool != null) {
            // We may get back a different transaction object.
            tx = memoryPool.seen(tx, getAddress());
        }
        final Transaction fTx = tx;
        if (maybeHandleRequestedData(fTx)) {
            return;
        }
        if (currentFilteredBlock != null) {
            if (!currentFilteredBlock.provideTransaction(tx)) {
                // Got a tx that didn't fit into the filtered block, so we must have received everything.
                processFilteredBlock(currentFilteredBlock);
                currentFilteredBlock = null;
            }
            // Don't tell wallets or listeners about this tx as they'll learn about it when the filtered block is
            // fully downloaded instead.
            return;
        }
        // It's a broadcast transaction. Tell all wallets about this tx so they can check if it's relevant or not.
        for (ListIterator<Wallet> it = wallets.listIterator(); it.hasNext();) {
            final Wallet wallet = it.next();
            try {
                if (wallet.isPendingTransactionRelevant(fTx)) {
                    // This transaction seems interesting to us, so let's download its dependencies. This has several
                    // purposes: we can check that the sender isn't attacking us by engaging in protocol abuse games,
                    // like depending on a time-locked transaction that will never confirm, or building huge chains
                    // of unconfirmed transactions (again - so they don't confirm and the money can be taken
                    // back with a Finney attack). Knowing the dependencies also lets us store them in a serialized
                    // wallet so we always have enough data to re-announce to the network and get the payment into
                    // the chain, in case the sender goes away and the network starts to forget.
                    // TODO: Not all the above things are implemented.

                    Futures.addCallback(downloadDependencies(fTx), new FutureCallback<List<Transaction>>() {
                        public void onSuccess(List<Transaction> dependencies) {
                            try {
                                log.info("{}: Dependency download complete!", address);
                                wallet.receivePending(fTx, dependencies);
                            } catch (VerificationException e) {
                                log.error("{}: Wallet failed to process pending transaction {}",
                                        address, fTx.getHashAsString());
                                log.error("Error was: ", e);
                                // Not much more we can do at this point.
                            }
                        }

                        public void onFailure(Throwable throwable) {
                            log.error("Could not download dependencies of tx {}", fTx.getHashAsString());
                            log.error("Error was: ", throwable);
                            // Not much more we can do at this point.
                        }
                    });

                }
            } catch (VerificationException e) {
                log.error("Wallet failed to verify tx", e);
                // Carry on, listeners may still want to know.
            }
        }
        // Tell all listeners about this tx so they can decide whether to keep it or not. If no listener keeps a
        // reference around then the memory pool will forget about it after a while too because it uses weak references.
        EventListenerInvoker.invoke(eventListeners, new EventListenerInvoker<PeerEventListener>() {
            @Override
            public void invoke(PeerEventListener listener) {
                listener.onTransaction(Peer.this, fTx);
            }
        });
    }

    /**
     * <p>Returns a future that wraps a list of all transactions that the given transaction depends on, recursively.
     * Only transactions in peers memory pools are included; the recursion stops at transactions that are in the
     * current best chain. So it doesn't make much sense to provide a tx that was already in the best chain and
     * a precondition checks this.</p>
     *
     * <p>For example, if tx has 2 inputs that connect to transactions A and B, and transaction B is unconfirmed and
     * has one input connecting to transaction C that is unconfirmed, and transaction C connects to transaction D
     * that is in the chain, then this method will return either {B, C} or {C, B}. No ordering is guaranteed.</p>
     *
     * <p>This method is useful for apps that want to learn about how long an unconfirmed transaction might take
     * to confirm, by checking for unexpectedly time locked transactions, unusually deep dependency trees or fee-paying
     * transactions that depend on unconfirmed free transactions.</p>
     *
     * <p>Note that dependencies downloaded this way will not trigger the onTransaction method of event listeners.</p>
     */
    public ListenableFuture<List<Transaction>> downloadDependencies(Transaction tx) {
        TransactionConfidence.ConfidenceType txConfidence = tx.getConfidence().getConfidenceType();
        Preconditions.checkArgument(txConfidence != TransactionConfidence.ConfidenceType.BUILDING);
        log.info("{}: Downloading dependencies of {}", address, tx.getHashAsString());
        final LinkedList<Transaction> results = new LinkedList<Transaction>();
        // future will be invoked when the entire dependency tree has been walked and the results compiled.
        final ListenableFuture future = downloadDependenciesInternal(tx, new Object(), results);
        final SettableFuture<List<Transaction>> resultFuture = SettableFuture.create();
        Futures.addCallback(future, new FutureCallback() {
            public void onSuccess(Object _) {
                resultFuture.set(results);
            }

            public void onFailure(Throwable throwable) {
                resultFuture.setException(throwable);
            }
        });
        return resultFuture;
    }

    // The marker object in the future returned is the same as the parameter. It is arbitrary and can be anything.
    private ListenableFuture<Object> downloadDependenciesInternal(final Transaction tx,
                                                                  final Object marker,
                                                                  final List<Transaction> results) {
        final SettableFuture<Object> resultFuture = SettableFuture.create();
        final Sha256Hash rootTxHash = tx.getHash();
        // We want to recursively grab its dependencies. This is so listeners can learn important information like
        // whether a transaction is dependent on a timelocked transaction or has an unexpectedly deep dependency tree
        // or depends on a no-fee transaction.
        //
        // Firstly find any that are already in the memory pool so if they weren't garbage collected yet, they won't
        // be deleted. Use COW sets to make unit tests deterministic and because they are small. It's slower for
        // the case of transactions with tons of inputs.
        Set<Transaction> dependencies = new CopyOnWriteArraySet<Transaction>();
        Set<Sha256Hash> needToRequest = new CopyOnWriteArraySet<Sha256Hash>();
        for (TransactionInput input : tx.getInputs()) {
            // There may be multiple inputs that connect to the same transaction.
            Sha256Hash hash = input.getOutpoint().getHash();
            synchronized (this) {
                Transaction dep = memoryPool.get(hash);
                if (dep == null) {
                    needToRequest.add(hash);
                } else {
                    dependencies.add(dep);
                }
            }
        }
        results.addAll(dependencies);
        try {
            // Build the request for the missing dependencies.
            List<ListenableFuture<Transaction>> futures = Lists.newArrayList();
            GetDataMessage getdata = new GetDataMessage(params);
            final long nonce = (long)(Math.random()*Long.MAX_VALUE);
            for (Sha256Hash hash : needToRequest) {
                getdata.addTransaction(hash);
                GetDataRequest req = new GetDataRequest();
                req.hash = hash;
                req.future = SettableFuture.create();
                if (!isNotFoundMessageSupported()) {
                    req.nonce = nonce;
                }
                futures.add(req.future);
                getDataFutures.add(req);
            }
            // The transactions we already grabbed out of the mempool must still be considered by the code below.
            for (Transaction dep : dependencies) {
                futures.add(Futures.immediateFuture(dep));
            }
            ListenableFuture<List<Transaction>> successful = Futures.successfulAsList(futures);
            Futures.addCallback(successful, new FutureCallback<List<Transaction>>() {
                public void onSuccess(List<Transaction> transactions) {
                    // Once all transactions either were received, or we know there are no more to come ...
                    // Note that transactions will contain "null" for any positions that weren't successful.
                    List<ListenableFuture<Object>> childFutures = Lists.newLinkedList();
                    for (Transaction tx : transactions) {
                        if (tx == null) continue;
                        log.info("{}: Downloaded dependency of {}: {}", new Object[]{address, rootTxHash, tx.getHashAsString()});
                        results.add(tx);
                        // Now recurse into the dependencies of this transaction too.
                        childFutures.add(downloadDependenciesInternal(tx, marker, results));
                    }
                    if (childFutures.size() == 0) {
                        // Short-circuit: we're at the bottom of this part of the tree.
                        resultFuture.set(marker);
                    } else {
                        // There are some children to download. Wait until it's done (and their children and their
                        // children...) to inform the caller that we're finished.
                        Futures.addCallback(Futures.successfulAsList(childFutures), new FutureCallback<List<Object>>() {
                            public void onSuccess(List<Object> objects) {
                                resultFuture.set(marker);
                            }

                            public void onFailure(Throwable throwable) {
                                resultFuture.setException(throwable);
                            }
                        });
                    }
                }

                public void onFailure(Throwable throwable) {
                    resultFuture.setException(throwable);
                }
            });
            // Start the operation.
            sendMessage(getdata);
            if (!isNotFoundMessageSupported()) {
                // If the peer isn't new enough to support the notfound message, we use a nasty hack instead and
                // assume if we send a ping message after the getdata message, it'll be processed after all answers
                // from getdata are done, so we can watch for the pong message as a substitute.
                ping(nonce).addListener(new Runnable() {
                    public void run() {
                        // The pong came back so clear out any transactions we requested but didn't get.
                        for (ListIterator<GetDataRequest> it = getDataFutures.listIterator(); it.hasNext();) {
                            GetDataRequest req = it.next();
                            if (req.nonce == nonce) {
                                req.future.cancel(true);
                                getDataFutures.remove(req);
                                break;
                            }
                        }
                    }
                }, MoreExecutors.sameThreadExecutor());
            }
        } catch (Exception e) {
            log.error("Couldn't send getdata in downloadDependencies({})", tx.getHash());
            resultFuture.setException(e);
            return resultFuture;
        }
        return resultFuture;
    }

    private synchronized void processBlock(Block m) throws IOException {
        log.debug("{}: Received broadcast block {}", address, m.getHashAsString());
        try {
            // Was this block requested by getBlock()?
            if (maybeHandleRequestedData(m)) return;

            if (!downloadData) {
                // This can happen if we lose download peer status after requesting block data.
                log.debug("{}: Received block we did not ask for: {}", address, m.getHashAsString());
                return;
            }
            pendingBlockDownloads.remove(m.getHash());
            // Otherwise it's a block sent to us because the peer thought we needed it, so add it to the block chain.
            // This call will synchronize on blockChain.
            if (blockChain.add(m)) {
                // The block was successfully linked into the chain. Notify the user of our progress.
                invokeOnBlocksDownloaded(m);
            } else {
                // This block is an orphan - we don't know how to get from it back to the genesis block yet. That
                // must mean that there are blocks we are missing, so do another getblocks with a new block locator
                // to ask the peer to send them to us. This can happen during the initial block chain download where
                // the peer will only send us 500 at a time and then sends us the head block expecting us to request
                // the others.
                //
                // We must do two things here:
                // (1) Request from current top of chain to the oldest ancestor of the received block in the orphan set
                // (2) Filter out duplicate getblock requests (done in blockChainDownload).
                //
                // The reason for (1) is that otherwise if new blocks were solved during the middle of chain download
                // we'd do a blockChainDownload() on the new best chain head, which would cause us to try and grab the
                // chain twice (or more!) on the same connection! The block chain would filter out the duplicates but
                // only at a huge speed penalty. By finding the orphan root we ensure every getblocks looks the same
                // no matter how many blocks are solved, and therefore that the (2) duplicate filtering can work.
                blockChainDownload(blockChain.getOrphanRoot(m.getHash()).getHash());
            }
        } catch (VerificationException e) {
            // We don't want verification failures to kill the thread.
            log.warn("{}: Block verification failed", address, e);
        } catch (PrunedException e) {
            // Unreachable when in SPV mode.
            throw new RuntimeException(e);
        }
    }

    // TODO: Fix this duplication.
    private synchronized void processFilteredBlock(FilteredBlock m) throws IOException {
        log.debug("{}: Received broadcast filtered block {}", address, m.getHash().toString());
        try {
            if (!downloadData) {
                log.debug("{}: Received block we did not ask for: {}", address, m.getHash().toString());
                return;
            }
            
            // Note that we currently do nothing about peers which do not include transactions which
            // actually match our filter or which do not send us all the transactions (TODO: Do something about that).
            
            pendingBlockDownloads.remove(m.getBlockHeader().getHash());
            // Otherwise it's a block sent to us because the peer thought we needed it, so add it to the block chain.
            // This call will synchronize on blockChain.
            if (blockChain.add(m)) {
                // The block was successfully linked into the chain. Notify the user of our progress.
                invokeOnBlocksDownloaded(m.getBlockHeader());
            } else {
                // This block is an orphan - we don't know how to get from it back to the genesis block yet. That
                // must mean that there are blocks we are missing, so do another getblocks with a new block locator
                // to ask the peer to send them to us. This can happen during the initial block chain download where
                // the peer will only send us 500 at a time and then sends us the head block expecting us to request
                // the others.
                //
                // We must do two things here:
                // (1) Request from current top of chain to the oldest ancestor of the received block in the orphan set
                // (2) Filter out duplicate getblock requests (done in blockChainDownload).
                //
                // The reason for (1) is that otherwise if new blocks were solved during the middle of chain download
                // we'd do a blockChainDownload() on the new best chain head, which would cause us to try and grab the
                // chain twice (or more!) on the same connection! The block chain would filter out the duplicates but
                // only at a huge speed penalty. By finding the orphan root we ensure every getblocks looks the same
                // no matter how many blocks are solved, and therefore that the (2) duplicate filtering can work.
                blockChainDownload(blockChain.getOrphanRoot(m.getHash()).getHash());
            }
        } catch (VerificationException e) {
            // We don't want verification failures to kill the thread.
            log.warn("{}: FilteredBlock verification failed", address, e);
        } catch (PrunedException e) {
            // We pruned away some of the data we need to properly handle this block. We need to request the needed
            // data from the remote peer and fix things. Or just give up.
            // TODO: Request e.getHash() and submit it to the block store before any other blocks
            throw new RuntimeException(e);
        }
    }

    private boolean maybeHandleRequestedData(Message m) {
        boolean found = false;
        Sha256Hash hash = m.getHash();
        for (ListIterator<GetDataRequest> it = getDataFutures.listIterator(); it.hasNext();) {
            GetDataRequest req = it.next();
            if (hash.equals(req.hash)) {
                req.future.set(m);
                getDataFutures.remove(req);
                found = true;
                // Keep going in case there are more.
            }
        }
        return found;
    }

    private synchronized void invokeOnBlocksDownloaded(final Block m) {
        // It is possible for the peer block height difference to be negative when blocks have been solved and broadcast
        // since the time we first connected to the peer. However, it's weird and unexpected to receive a callback
        // with negative "blocks left" in this case, so we clamp to zero so the API user doesn't have to think about it.
        final int blocksLeft = Math.max(0, (int)peerVersionMessage.bestHeight - blockChain.getBestChainHeight());
        EventListenerInvoker.invoke(eventListeners, new EventListenerInvoker<PeerEventListener>() {
            @Override
            public void invoke(PeerEventListener listener) {
                listener.onBlocksDownloaded(Peer.this, m, blocksLeft);
            }
        });
    }

    private synchronized void processInv(InventoryMessage inv) throws IOException {
        // This should be called in the network loop thread for this peer.
        List<InventoryItem> items = inv.getItems();

        // Separate out the blocks and transactions, we'll handle them differently
        List<InventoryItem> transactions = new LinkedList<InventoryItem>();
        List<InventoryItem> blocks = new LinkedList<InventoryItem>();

        for (InventoryItem item : items) {
            switch (item.type) {
                case Transaction: transactions.add(item); break;
                case Block: blocks.add(item); break;
                default: throw new IllegalStateException("Not implemented: " + item.type);
            }
        }

        if (transactions.size() == 0 && blocks.size() == 1) {
            // Single block announcement. If we're downloading the chain this is just a tickle to make us continue
            // (the block chain download protocol is very implicit and not well thought out). If we're not downloading
            // the chain then this probably means a new block was solved and the peer believes it connects to the best
            // chain, so count it. This way getBestChainHeight() can be accurate.
            if (downloadData) {
                if (!blockChain.isOrphan(blocks.get(0).hash)) {
                    blocksAnnounced++;
                }
            } else {
                blocksAnnounced++;
            }
        }

        GetDataMessage getdata = new GetDataMessage(params);

        Iterator<InventoryItem> it = transactions.iterator();
        while (it.hasNext()) {
            InventoryItem item = it.next();
            if (memoryPool == null) {
                if (downloadData) {
                    // If there's no memory pool only download transactions if we're configured to.
                    getdata.addItem(item);
                }
            } else {
                // Only download the transaction if we are the first peer that saw it be advertised. Other peers will also
                // see it be advertised in inv packets asynchronously, they co-ordinate via the memory pool. We could
                // potentially download transactions faster by always asking every peer for a tx when advertised, as remote
                // peers run at different speeds. However to conserve bandwidth on mobile devices we try to only download a
                // transaction once. This means we can miss broadcasts if the peer disconnects between sending us an inv and
                // sending us the transaction: currently we'll never try to re-fetch after a timeout.
                if (memoryPool.maybeWasSeen(item.hash)) {
                    // Some other peer already announced this so don't download.
                    it.remove();
                } else {
                    log.debug("{}: getdata on tx {}", address, item.hash);
                    getdata.addItem(item);
                }
                memoryPool.seen(item.hash, this.getAddress());
            }
        }
        
        // If we are requesting filteredblocks we have to send a ping after the getdata so that we have a clear
        // end to the final FilteredBlock's transactions (in the form of a pong) sent to us
        boolean pingAfterGetData = false;

        if (blocks.size() > 0 && downloadData && blockChain != null) {
            // Ideally, we'd only ask for the data here if we actually needed it. However that can imply a lot of
            // disk IO to figure out what we've got. Normally peers will not send us inv for things we already have
            // so we just re-request it here, and if we get duplicates the block chain / wallet will filter them out.
            for (InventoryItem item : blocks) {
                if (blockChain.isOrphan(item.hash)) {
                    // If an orphan was re-advertised, ask for more blocks.
                    blockChainDownload(blockChain.getOrphanRoot(item.hash).getHash());
                } else {
                    // Don't re-request blocks we already requested. Normally this should not happen. However there is
                    // an edge case: if a block is solved and we complete the inv<->getdata<->block<->getblocks cycle
                    // whilst other parts of the chain are streaming in, then the new getblocks request won't match the
                    // previous one: whilst the stopHash is the same (because we use the orphan root), the start hash
                    // will be different and so the getblocks req won't be dropped as a duplicate. We'll end up
                    // requesting a subset of what we already requested, which can lead to parallel chain downloads
                    // and other nastyness. So we just do a quick removal of redundant getdatas here too.
                    //
                    // Note that as of June 2012 the Satoshi client won't actually ever interleave blocks pushed as
                    // part of chain download with newly announced blocks, so it should always be taken care of by
                    // the duplicate check in blockChainDownload(). But the satoshi client may change in future so
                    // it's better to be safe here.
                    if (!pendingBlockDownloads.contains(item.hash)) {
                        if (getPeerVersionMessage().clientVersion > 70000 && useFilteredBlocks) {
                            getdata.addItem(new InventoryItem(InventoryItem.Type.FilteredBlock, item.hash));
                            pingAfterGetData = true;
                        } else
                            getdata.addItem(item);
                        pendingBlockDownloads.add(item.hash);
                    }
                }
            }
            // If we're downloading the chain, doing a getdata on the last block we were told about will cause the
            // peer to advertize the head block to us in a single-item inv. When we download THAT, it will be an
            // orphan block, meaning we'll re-enter blockChainDownload() to trigger another getblocks between the
            // current best block we have and the orphan block. If more blocks arrive in the meantime they'll also
            // become orphan.
        }

        if (!getdata.getItems().isEmpty()) {
            // This will cause us to receive a bunch of block or tx messages.
            sendMessage(getdata);
        }
        
        if (pingAfterGetData)
            sendMessage(new Ping((long) Math.random() * Long.MAX_VALUE));
    }

    /**
     * Asks the connected peer for the block of the given hash, and returns a future representing the answer.
     * If you want the block right away and don't mind waiting for it, just call .get() on the result. Your thread
     * will block until the peer answers.
     */
    public ListenableFuture<Block> getBlock(Sha256Hash blockHash) throws IOException {
        log.info("Request to fetch block {}", blockHash);
        GetDataMessage getdata = new GetDataMessage(params);
        getdata.addBlock(blockHash);
        return sendSingleGetData(getdata);
    }

    /**
     * Asks the connected peer for the given transaction from its memory pool. Transactions in the chain cannot be
     * retrieved this way because peers don't have a transaction ID to transaction-pos-on-disk index, and besides,
     * in future many peers will delete old transaction data they don't need.
     */
    public ListenableFuture<Transaction> getPeerMempoolTransaction(Sha256Hash hash) throws IOException {
        // TODO: Unit test this method.
        log.info("Request to fetch peer mempool tx  {}", hash);
        GetDataMessage getdata = new GetDataMessage(params);
        getdata.addTransaction(hash);
        return sendSingleGetData(getdata);
    }

    /** Sends a getdata with a single item in it. */
    private ListenableFuture sendSingleGetData(GetDataMessage getdata) throws IOException {
        Preconditions.checkArgument(getdata.getItems().size() == 1);
        GetDataRequest req = new GetDataRequest();
        req.future = SettableFuture.create();
        req.hash = getdata.getItems().get(0).hash;
        getDataFutures.add(req);
        sendMessage(getdata);
        return req.future;
    }

    /**
     * When downloading the block chain, the bodies will be skipped for blocks created before the given date. Any
     * transactions relevant to the wallet will therefore not be found, but if you know your wallet has no such
     * transactions it doesn't matter and can save a lot of bandwidth and processing time. Note that the times of blocks
     * isn't known until their headers are available and they are requested in chunks, so some headers may be downloaded
     * twice using this scheme, but this optimization can still be a large win for newly created wallets.
     *
     * @param secondsSinceEpoch Time in seconds since the epoch or 0 to reset to always downloading block bodies.
     */
    public synchronized void setDownloadParameters(long secondsSinceEpoch, boolean useFilteredBlocks) {
        Preconditions.checkNotNull(blockChain);
        if (secondsSinceEpoch == 0) {
            fastCatchupTimeSecs = params.genesisBlock.getTimeSeconds();
            downloadBlockBodies = true;
        } else {
            fastCatchupTimeSecs = secondsSinceEpoch;
            // If the given time is before the current chains head block time, then this has no effect (we already
            // downloaded everything we need).
            if (fastCatchupTimeSecs > blockChain.getChainHead().getHeader().getTimeSeconds()) {
                downloadBlockBodies = false;
            }
        }
        this.useFilteredBlocks = useFilteredBlocks;
    }

    /**
     * Links the given wallet to this peer. If you have multiple peers, you should use a {@link PeerGroup} to manage
     * them and use the {@link PeerGroup#addWallet(Wallet)} method instead of registering the wallet with each peer
     * independently, otherwise the wallet will receive duplicate notifications.
     */
    public void addWallet(Wallet wallet) {
        wallets.add(wallet);
    }

    /** Unlinks the given wallet from peer. See {@link Peer#addWallet(Wallet)}. */
    public void removeWallet(Wallet wallet) {
        wallets.remove(wallet);
    }

    /**
     * Sends the given message on the peers Channel.
     */
    public ChannelFuture sendMessage(Message m) throws IOException {
        return Channels.write(channel, m);
    }

    // Keep track of the last request we made to the peer in blockChainDownload so we can avoid redundant and harmful
    // getblocks requests. This does not have to be synchronized because blockChainDownload cannot be called from
    // multiple threads simultaneously.
    private Sha256Hash lastGetBlocksBegin, lastGetBlocksEnd;

    private synchronized void blockChainDownload(Sha256Hash toHash) throws IOException {
        // This may run in ANY thread.

        // The block chain download process is a bit complicated. Basically, we start with one or more blocks in a
        // chain that we have from a previous session. We want to catch up to the head of the chain BUT we don't know
        // where that chain is up to or even if the top block we have is even still in the chain - we
        // might have got ourselves onto a fork that was later resolved by the network.
        //
        // To solve this, we send the peer a block locator which is just a list of block hashes. It contains the
        // blocks we know about, but not all of them, just enough of them so the peer can figure out if we did end up
        // on a fork and if so, what the earliest still valid block we know about is likely to be.
        //
        // Once it has decided which blocks we need, it will send us an inv with up to 500 block messages. We may
        // have some of them already if we already have a block chain and just need to catch up. Once we request the
        // last block, if there are still more to come it sends us an "inv" containing only the hash of the head
        // block.
        //
        // That causes us to download the head block but then we find (in processBlock) that we can't connect
        // it to the chain yet because we don't have the intermediate blocks. So we rerun this function building a
        // new block locator describing where we're up to.
        //
        // The getblocks with the new locator gets us another inv with another bunch of blocks. We download them once
        // again. This time when the peer sends us an inv with the head block, we already have it so we won't download
        // it again - but we recognize this case as special and call back into blockChainDownload to continue the
        // process.
        //
        // So this is a complicated process but it has the advantage that we can download a chain of enormous length
        // in a relatively stateless manner and with constant memory usage.
        //
        // All this is made more complicated by the desire to skip downloading the bodies of blocks that pre-date the
        // 'fast catchup time', which is usually set to the creation date of the earliest key in the wallet. Because
        // we know there are no transactions using our keys before that date, we need only the headers. To do that we
        // use the "getheaders" command. Once we find we've gone past the target date, we throw away the downloaded
        // headers and then request the blocks from that point onwards. "getheaders" does not send us an inv, it just
        // sends us the data we requested in a "headers" message.

        // TODO: Block locators should be abstracted out rather than special cased here.
        List<Sha256Hash> blockLocator = new ArrayList<Sha256Hash>(51);
        // For now we don't do the exponential thinning as suggested here:
        //
        //   https://en.bitcoin.it/wiki/Protocol_specification#getblocks
        //
        // This is because it requires scanning all the block chain headers, which is very slow. Instead we add the top
        // 50 block headers. If there is a re-org deeper than that, we'll end up downloading the entire chain. We
        // must always put the genesis block as the first entry.
        BlockStore store = blockChain.getBlockStore();
        StoredBlock chainHead = blockChain.getChainHead();
        Sha256Hash chainHeadHash = chainHead.getHeader().getHash();
        // Did we already make this request? If so, don't do it again.
        if (Objects.equal(lastGetBlocksBegin, chainHeadHash) && Objects.equal(lastGetBlocksEnd, toHash)) {
            log.info("blockChainDownload({}): ignoring duplicated request", toHash.toString());
            return;
        }
        log.info("{}: blockChainDownload({}) current head = {}", new Object[] { toString(),
                toHash.toString(), chainHead.getHeader().getHashAsString() });
        StoredBlock cursor = chainHead;
        for (int i = 100; cursor != null && i > 0; i--) {
            blockLocator.add(cursor.getHeader().getHash());
            try {
                cursor = cursor.getPrev(store);
            } catch (BlockStoreException e) {
                log.error("Failed to walk the block chain whilst constructing a locator");
                throw new RuntimeException(e);
            }
        }
        // Only add the locator if we didn't already do so. If the chain is < 50 blocks we already reached it.
        if (cursor != null) {
            blockLocator.add(params.genesisBlock.getHash());
        }

        // Record that we requested this range of blocks so we can filter out duplicate requests in the event of a
        // block being solved during chain download.
        lastGetBlocksBegin = chainHeadHash;
        lastGetBlocksEnd = toHash;

        if (downloadBlockBodies) {
            GetBlocksMessage message = new GetBlocksMessage(params, blockLocator, toHash);
            sendMessage(message);
        } else {
            // Downloading headers for a while instead of full blocks.
            GetHeadersMessage message = new GetHeadersMessage(params, blockLocator, toHash);
            sendMessage(message);
        }
    }

    /**
     * Starts an asynchronous download of the block chain. The chain download is deemed to be complete once we've
     * downloaded the same number of blocks that the peer advertised having in its version handshake message.
     */
    public synchronized void startBlockChainDownload() throws IOException {
        setDownloadData(true);
        // TODO: peer might still have blocks that we don't have, and even have a heavier
        // chain even if the chain block count is lower.
        if (getPeerBlockHeightDifference() >= 0) {
            EventListenerInvoker.invoke(eventListeners, new EventListenerInvoker<PeerEventListener>() {
                @Override
                public void invoke(PeerEventListener listener) {
                    listener.onChainDownloadStarted(Peer.this, getPeerBlockHeightDifference());
                }
            });

            // When we just want as many blocks as possible, we can set the target hash to zero.
            blockChainDownload(Sha256Hash.ZERO_HASH);
        }
    }

    private class PendingPing {
        // The future that will be invoked when the pong is heard back.
        public SettableFuture<Long> future;
        // The random nonce that lets us tell apart overlapping pings/pongs.
        public long nonce;
        // Measurement of the time elapsed.
        public long startTimeMsec;

        public PendingPing(long nonce) {
            future = SettableFuture.create();
            this.nonce = nonce;
            startTimeMsec = Utils.now().getTime();
        }

        public void complete() {
            Preconditions.checkNotNull(future, "Already completed");
            Long elapsed = Long.valueOf(Utils.now().getTime() - startTimeMsec);
            Peer.this.addPingTimeData(elapsed.longValue());
            log.debug("{}: ping time is {} msec", Peer.this.toString(), elapsed);
            future.set(elapsed);
            future = null;
        }
    }

    /** Adds a ping time sample to the averaging window. */
    private synchronized void addPingTimeData(long sample) {
        if (lastPingTimes == null) {
            lastPingTimes = new long[PING_MOVING_AVERAGE_WINDOW];
            // Initialize the averaging window to the first sample.
            Arrays.fill(lastPingTimes, sample);
        } else {
            // Shift all elements backwards by one.
            System.arraycopy(lastPingTimes, 1, lastPingTimes, 0, lastPingTimes.length - 1);
            // And append the new sample to the end.
            lastPingTimes[lastPingTimes.length - 1] = sample;
        }
    }

    /**
     * Sends the peer a ping message and returns a future that will be invoked when the pong is received back.
     * The future provides a number which is the number of milliseconds elapsed between the ping and the pong.
     * Once the pong is received the value returned by {@link com.google.bitcoin.core.Peer#getLastPingTime()} is
     * updated.
     * @throws ProtocolException if the peer version is too low to support measurable pings.
     */
    public synchronized ListenableFuture<Long> ping() throws IOException, ProtocolException {
        return ping((long) Math.random() * Long.MAX_VALUE);
    }

    protected synchronized ListenableFuture<Long> ping(long nonce) throws IOException, ProtocolException {
        int peerVersion = getPeerVersionMessage().clientVersion;
        if (peerVersion < Pong.MIN_PROTOCOL_VERSION)
            throw new ProtocolException("Peer version is too low for measurable pings: " + peerVersion);
        PendingPing pendingPing = new PendingPing(nonce);
        pendingPings.add(pendingPing);
        sendMessage(new Ping(pendingPing.nonce));
        return pendingPing.future;
    }

    /**
     * Returns the elapsed time of the last ping/pong cycle. If {@link com.google.bitcoin.core.Peer#ping()} has never
     * been called or we did not hear back the "pong" message yet, returns {@link Long#MAX_VALUE}.
     */
    public synchronized long getLastPingTime() {
        if (lastPingTimes == null)
            return Long.MAX_VALUE;
        return lastPingTimes[lastPingTimes.length - 1];
    }

    /**
     * Returns a moving average of the last N ping/pong cycles. If {@link com.google.bitcoin.core.Peer#ping()} has never
     * been called or we did not hear back the "pong" message yet, returns {@link Long#MAX_VALUE}. The moving average
     * window is 5 buckets.
     */
    public synchronized long getPingTime() {
        if (lastPingTimes == null)
            return Long.MAX_VALUE;
        long sum = 0;
        for (long i : lastPingTimes) sum += i;
        return (long)((double) sum / lastPingTimes.length);
    }

    private void processPong(Pong m) {
        PendingPing ping = null;
        // Iterates over a snapshot of the list, so we can run unlocked here.
        ListIterator<PendingPing> it = pendingPings.listIterator();
        while (it.hasNext()) {
            ping = it.next();
            if (m.getNonce() == ping.nonce) {
                pendingPings.remove(ping);
                break;
            }
        }
        // This line may trigger an event listener being run on the same thread, if one is attached to the
        // pending ping future. That event listener may in turn re-run ping, so we need to do it last.
        if (ping != null) ping.complete();
    }

    /**
     * Returns the difference between our best chain height and the peers, which can either be positive if we are
     * behind the peer, or negative if the peer is ahead of us.
     */
    public synchronized int getPeerBlockHeightDifference() {
        // Chain will overflow signed int blocks in ~41,000 years.
        int chainHeight = (int) getBestHeight();
        // chainHeight should not be zero/negative because we shouldn't have given the user a Peer that is to another
        // client-mode node, nor should it be unconnected. If that happens it means the user overrode us somewhere or
        // there is a bug in the peer management code.
        Preconditions.checkState(params.allowEmptyPeerChains || chainHeight > 0, "Connected to peer with zero/negative chain height", chainHeight);
        return chainHeight - blockChain.getBestChainHeight();
    }

    private boolean isNotFoundMessageSupported() {
        return getPeerVersionMessage().clientVersion >= 70001;
    }

    /**
     * Returns true if this peer will try and download things it is sent in "inv" messages. Normally you only need
     * one peer to be downloading data. Defaults to true.
     */
    public synchronized boolean getDownloadData() {
        return downloadData;
    }

    /**
     * If set to false, the peer won't try and fetch blocks and transactions it hears about. Normally, only one
     * peer should download missing blocks. Defaults to true.
     */
    public synchronized void setDownloadData(boolean downloadData) {
        this.downloadData = downloadData;
    }

    /**
     * @return the IP address and port of peer.
     */
    public synchronized PeerAddress getAddress() {
        return address;
    }

    /**
     * @return various version numbers claimed by peer.
     */
    public synchronized VersionMessage getPeerVersionMessage() {
      return peerVersionMessage;
    }

    /**
     * @return various version numbers we claim.
     */
    public synchronized VersionMessage getVersionMessage() {
      return versionMessage;
    }

    /**
     * @return the height of the best chain as claimed by peer: sum of its ver announcement and blocks announced since.
     */
    public synchronized long getBestHeight() {
      return peerVersionMessage.bestHeight + blocksAnnounced;
    }

    /**
     * The minimum P2P protocol version that is accepted. If the peer speaks a protocol version lower than this, it
     * will be disconnected.
     * @return if not-null then this is the future for the Peer disconnection event.
     */
    public ChannelFuture setMinProtocolVersion(int minProtocolVersion) {
        synchronized (this) {
            this.minProtocolVersion = minProtocolVersion;
        }
        if (getVersionMessage().clientVersion < minProtocolVersion) {
            log.warn("{}: Disconnecting due to new min protocol version {}", this, minProtocolVersion);
            return Channels.close(channel);
        } else {
            return null;
        }
    }
}
