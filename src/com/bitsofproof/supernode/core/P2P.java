/**
* Copyright 2012 Tamas Blummer
* 
* High performance Peer to Peer client and server using NIO.
* 
* USAGE
* 1. Derive your YourPeer from P2P.Peer, your YourMessage from P2P.Message
* 2. implement their abstract methods (see below)
* 3. Derive your P2PNetwork from P2P
* 4. implement createPeer to create your peer object for an address
* 5. Instantiate P2PNetwork
* 7. add some peer addresses using addPeer ()
* 8. call P2PNetwork.start ()
* 9. enjoy:
* 		P2PNetwork.createPeer will be called to instantiate your Peers
* 		YourPeer.onConnect () will be called first. send (YourMessage) what is needed to introduce your peer 
* 						no receive will be called until this returns.
* 		YourMessage YourPeer.parse (InputStream in) should instantiate any message from wire
* 						do not do any other processing here
* 		YourPeer.receive(Message) should do the message processing. 
* 						This might call send (), addPeer () or disconnect () but nothing else from this framework 
* 
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
package com.bitsofproof.supernode.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class P2P {
	private static final Logger log = LoggerFactory.getLogger(P2P.class);
	
	private final static int BUFFSIZE = 8*1024;
	
	public interface Message
	{
		public byte [] toByteArray () throws Exception;
		public String dump ();
	}
	
	public abstract class Peer {
		private InetSocketAddress address;
		private SocketChannel channel;
		private Semaphore writeable = new Semaphore (1);
		
		private LinkedBlockingQueue<byte[]> writes = new LinkedBlockingQueue<byte[]>();
		private LinkedBlockingQueue<byte[]> reads = new LinkedBlockingQueue<byte[]>();
		private ByteArrayInputStream currentRead = null;
		private final Semaphore notYetListened = new Semaphore (1);
		private ByteBuffer pushBackBuffer = null;
		
		private InputStream readIn = new InputStream() {
			public int read(byte[] b, int off, int len) throws IOException {
				int need = len;
				if (need <= 0)
					return need;
				do {
					if (currentRead != null) {
						int r = currentRead.read(b, off, need);
						if (r > 0) {
							off += r;
							need -= r;
						}
					}
					if (need == 0) {
						return len;
					}
					byte[] buf = null;
					try {
						buf = reads.poll(READTIMEOUT, TimeUnit.SECONDS);
						if (buf == null)
						{
							return -1;
						}
					} catch (InterruptedException e) {
						throw new IOException(e);
					}
					currentRead = new ByteArrayInputStream(buf);
				} while (need > 0);
				return len;
			}

			public int read(byte[] b) throws IOException {
				return read(b, 0, b.length);
			}

			public int read() throws IOException {
				byte[] b = new byte[1];
				return read(b, 0, 1);
			}
		};

		public Peer(InetSocketAddress address) {
			this.address = address;
		}

		private void connect() {
			try {
				channel = SocketChannel.open();
				connectedPeers.put(channel, this);				
				channel.configureBlocking(false);
				channel.connect(address);
				selectorChanges.add(new ChangeRequest(channel, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT));
				selector.wakeup();
			} catch (IOException e) {
			}
		}

		@Override
		public boolean equals(Object obj) {
			return address.equals(((Peer) obj).address);
		}

		@Override
		public int hashCode() {
			return address.hashCode();
		}

		private void process(ByteBuffer buffer, int len) {
			if (len > 0) {
				byte[] b = new byte[len];
				System.arraycopy(buffer.array(), 0, b, 0, len);
				reads.add(b);
				if ( notYetListened.tryAcquire() )
					listen ();
			}
		}

		private ByteBuffer getBuffer() {
			if ( pushBackBuffer != null )
				return pushBackBuffer;

			byte[] next;
			
			if ((next = writes.poll()) != null)
			{
				return ByteBuffer.wrap(next);
			}
			return null;
		}
		
		private void pushBack (ByteBuffer b)
		{
			pushBackBuffer = b;
		}

		public InetSocketAddress getAddress() {
			return address;
		}

		public void disconnect() {
			try {
				// note that no other reference to peer is stored here
				// it might be garbage collected (that is probably the right thing to do)
				connectedPeers.remove(channel);
				channel.close();
			} catch (IOException e) {
			}
			connectSlot.release();
			onDisconnect ();
		}

		private void listen() {
			peerThreads.execute(new Runnable() {
				public void run() {
					Message m = null;
					try {
						m = parse(readIn);
						receive(m);
						peerThreads.execute(this); // listen again
					} 
					catch (Exception e) {
						log.error("Exception in message processing", e);
						disconnect();
					}
				}
			});
		}

		public void send(Message m) throws Exception {
			try
			{
				writeable.acquireUninterruptibly();
				
				byte []  wiremsg = m.toByteArray();
				int len = wiremsg.length;
				int off = 0;
				while ( len > 0 )
				{
					int s = Math.min(BUFFSIZE, len);
					byte [] b = new byte [s];
					System.arraycopy(wiremsg, off, b, 0, s);
					off +=s;
					writes.add(b);
					len -= s;
				}
				selectorChanges.add(new ChangeRequest((SocketChannel) channel, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));
				selector.wakeup();
			}
			finally
			{
				writeable.release ();
			}
		}

		public abstract Message parse(InputStream readIn) throws IOException;

		public abstract void receive(Message m);		

		public abstract void onConnect();

		public abstract void onDisconnect ();
		
	}
	
	public abstract Peer createPeer (InetSocketAddress address);
	
	public abstract boolean discover ();
	
	public void addPeer(InetAddress addr, int port) {
		InetSocketAddress address = new InetSocketAddress(addr, port);
		if ( !runqueue.contains(address) )
		{
			runqueue.add(address);
		}
	}

	public int getNumberOfConnections ()
	{
		return connectedPeers.size();
	}
	
	// peers connected
	private final Map<SocketChannel, Peer> connectedPeers = Collections.synchronizedMap(new HashMap<SocketChannel, Peer>());

	// peers waiting to be connected
	private final LinkedBlockingQueue<InetSocketAddress> runqueue = new LinkedBlockingQueue<InetSocketAddress>();

	// number of connections we try to maintain
	private final int desiredConnections;

	// we want fast answering nodes
	private static final int CONNECTIONTIMEOUT = 5;
	
	// number of seconds to wait until giving up on connections
	private static final int READTIMEOUT = 60; // seconds

	// keep track with number of connections we asked for here
	private final Semaphore connectSlot;
	
	private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	
	private int port;
	
	public void setPort (int port)
	{
		this.port = port;
	}
	public int getPort ()
	{
		return port;
	}
	
	public ScheduledExecutorService getScheduler ()
	{
		return scheduler;
	}
	
	private static class ChangeRequest {
		public static final int REGISTER = 1;
		public static final int CHANGEOPS = 2;

		public SelectableChannel socket;
		public int type;
		public int ops;

		public ChangeRequest(SelectableChannel socket, int type, int ops) {
			this.socket = socket;
			this.type = type;
			this.ops = ops;
		}
	}

	private final ConcurrentLinkedQueue<ChangeRequest> selectorChanges = new ConcurrentLinkedQueue<ChangeRequest>();

	private final Selector selector = Selector.open();
	
	private final Executor peerThreads;
	
	public P2P (int connections) throws IOException
	{
		desiredConnections = connections;
		connectSlot = new Semaphore(desiredConnections);
		// create a pool of threads
		peerThreads = Executors.newFixedThreadPool(Math.max(
				Math.min(desiredConnections/4,
							Runtime.getRuntime ().availableProcessors()*4),
							1), new ThreadFactory() {
			@Override
			public Thread newThread(final Runnable r) {
				Thread peerThread = new Thread() {
					@Override
					public void run() {
						r.run(); // just delegate
					}
				};
				peerThread.setDaemon(true); // let VM exit if only these remain
				peerThread.setName("Peer"); // name it for log
				return peerThread;
			}
		});
	}
	
	public void start() throws IOException {
		// create a server channel for the chain's port, work non-blocking and
		// wait for accept events
		final ServerSocketChannel serverChannel = ServerSocketChannel.open();
		serverChannel.socket().bind(new InetSocketAddress(port));
		serverChannel.configureBlocking(false);

		selectorChanges.add(new ChangeRequest(serverChannel, ChangeRequest.REGISTER, SelectionKey.OP_ACCEPT));
		selector.wakeup();
		
		// this thread waits on the selector above and acts on events
		Thread selectorThread = new Thread(new Runnable() {
			@Override
			public void run() {
				ByteBuffer readBuffer = ByteBuffer.allocate(BUFFSIZE);
				while (true) {
					try {
						ChangeRequest cr;
						while ((cr = selectorChanges.poll()) != null) {
							if ( cr.socket == null )
								continue;
							if (cr.type == ChangeRequest.REGISTER)
								cr.socket.register(selector, cr.ops);
							else if (cr.type == ChangeRequest.CHANGEOPS) {
								SelectionKey key = cr.socket.keyFor(selector);
								if ( key != null )
									key.interestOps(cr.ops);
							}
						}
						selector.select(); // wait for events
						Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
						while (keys.hasNext()) {
							SelectionKey key = keys.next();
							try {
								keys.remove();
								if ( !key.isValid() )
									continue;
								
								if (key.isAcceptable()) {
									// unsolicited request to connect
									final SocketChannel client = ((ServerSocketChannel) key.channel()).accept();
									client.configureBlocking(false);
									InetSocketAddress address = (InetSocketAddress) client.socket().getRemoteSocketAddress();
									final Peer peer;									
									peer = createPeer (address);
									peer.channel = client;
									if (connectSlot.tryAcquire()) {
										// if we have interest ...
										connectedPeers.put(client, peer);
										client.register(selector, SelectionKey.OP_READ);

										peerThreads.execute(new Runnable() {
											public void run() {
												peer.onConnect();
											}
										});
									} else {
										client.close();
										runqueue.add(address); // try later
										key.cancel();
									}
								}
								if (key.isConnectable()) {
									// we asked for connection here
									key.interestOps(SelectionKey.OP_READ);
									SocketChannel client = (SocketChannel) key.channel();
									client.finishConnect(); // finish
									InetSocketAddress address = (InetSocketAddress) client.socket().getRemoteSocketAddress();
									final Peer peer;
									if ( (peer = connectedPeers.get (client)) != null ) {
										if (connectSlot.tryAcquire()) {
											peerThreads.execute(new Runnable() {
												public void run() {
													peer.onConnect();
												}
											});
										} else {
											client.close();
											runqueue.add(address); // try again later
										}
									} else {
										client.close(); // do not know you
										key.cancel();
									}
								}
								if (key.isReadable()) {
									SocketChannel client = (SocketChannel) key.channel();
									final Peer peer = connectedPeers.get(client);
									if (peer != null) {
										try {
											int len = client.read(readBuffer);
											if (len > 0) {
												peer.process(readBuffer, len);
												readBuffer.clear();
											}
											else
											{
												peer.disconnect();
												key.cancel();
											}
										} catch (IOException e) {
											peer.disconnect();
											key.cancel();
										}
									}
									else
										key.cancel();
								}
								if (key.isWritable()) {
									SocketChannel client = (SocketChannel) key.channel();
									Peer peer = connectedPeers.get(client);
									if (peer != null) {
										try {
											ByteBuffer b;
											if ((b = peer.getBuffer()) != null)
											{
												client.write(b);
												int rest = b.remaining();
												if ( rest != 0 )
													peer.pushBack (b);
												else
													peer.pushBack (null);
											}
											else
												key.interestOps(SelectionKey.OP_READ);
										} catch (IOException e) {
											peer.disconnect();
											key.cancel();
										}
									}
									else
										key.cancel();
								}
							} catch (CancelledKeyException e) {
							} catch (Exception e) {
								log.error("Error processing a selector key", e);
								key.cancel();
							}
						}
					} catch (Exception e) {
						log.error("Unhandled Exception in selector thread", e);
					}
				}
			}
		});
		selectorThread.setDaemon(true);
		selectorThread.setName("Peer selector");
		selectorThread.start();

		// this thread keeps looking for new connections
		Thread connector = new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) { // forever
					try {
						if ( connectedPeers.size() >= desiredConnections )
						{
							Thread.sleep(1000);
						}
						else if ( connectSlot.availablePermits() > 0 )
						{
							InetSocketAddress address = runqueue.poll();
							if ( address != null )
							{
								for ( Peer p : connectedPeers.values() )
								{
									if ( p.address.equals(address) )
										continue;
								}
								final Peer peer = createPeer (address);
								peer.connect();
								scheduler.schedule(new Runnable (){
									@Override
									public void run() {
										if ( peer.channel.isConnectionPending() )
											try {
												// give up if not connected within CONNECTIONTIMEOUT
												log.trace("Give up connect on " + peer.channel);
												peer.channel.close();
												connectedPeers.remove(peer);
											} catch (IOException e) {
											}
									}}, CONNECTIONTIMEOUT, TimeUnit.SECONDS);
							}
							else
							{
								if ( connectedPeers.size() < desiredConnections )
								{
									log.info("Need to discover new adresses.");
									if ( !discover () )
									{
										break; // testing
									}
									if ( runqueue.size() == 0 )
									{
										log.error("Can not find new adresses");
										Thread.sleep(60*1000);
									}
								}
							}
						}
					} catch (Exception e) {
						log.error("Unhandled exception in peer queue", e);
					}
				}
			}
		});
		connector.setDaemon(true);
		connector.setName("Peer connector");
		connector.start();
		
		
	}
}
