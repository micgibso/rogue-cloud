/*
 * Copyright 2018 IBM Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
*/

package com.roguecloud.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roguecloud.RCRuntime;
import com.roguecloud.NG;
import com.roguecloud.client.ClientState;
import com.roguecloud.client.ISessionWrapper;
import com.roguecloud.client.utils.RCUtilLatencySim;
import com.roguecloud.client.utils.RCUtilLatencySim.ILatencySimReceiver;
import com.roguecloud.json.JsonClientConnect;
import com.roguecloud.json.JsonClientConnectResponse;
import com.roguecloud.json.JsonMessageMap;
import com.roguecloud.json.JsonClientConnectResponse.ConnectResult;
import com.roguecloud.utils.CompressionUtils;
import com.roguecloud.utils.Logger;

public class LibertySessionWrapper implements ISessionWrapper {

	private final static Logger log = Logger.getInstance();
	
	private final Object lock = new Object();
	
	private final ClientState parent;
	
	private final ClientStateOutputThread thread = new ClientStateOutputThread();
	
	private final HashMap<String /* session uuid */, OldSessionInfo> sessionRestartedNew_synch = new HashMap<>();
	
	private enum WrapperState { INITIAL, WAITING_FOR_CONFIRMATION, WAITING_FOR_RESEND, ACTIVE, COMPLETE }; 
	
	private WrapperState state_synch_lock;
	
	private static final long INITIAL_TIME_TO_WAIT_BETWEEN_CONN_FAILURES = 100l;
	private long timeToWaitBetweenConnectFailuresInMsecs = INITIAL_TIME_TO_WAIT_BETWEEN_CONN_FAILURES;
	
	private static final long MAX_TIME_TO_WAIT_BETWEEN_CONN_FAILURES = 10 * 1000l;
	
	private final LibertyClientEndpoint hce;
	
	private Session session_synch_lock;
	private Basic basicRemote_synch_lock;
	
	private boolean disposed = false;
	
	private String url_synch_lock  = null;

	private boolean isInitialConnect_synch_lock = true;
	
	private RCUtilLatencySim latencySim;
	
	public LibertySessionWrapper(ClientState parent) {
		this.parent = parent;
		this.hce = new LibertyClientEndpoint(this, parent);
		thread.start();
		
		if(RCRuntime.ENABLE_LATENCY_SIM) {
			latencySim = new RCUtilLatencySim();
		}
	}
	
	// This should only be called once.
	public void initialConnect(String url) {
		synchronized (lock) {
			url_synch_lock = url;
		}
		connect(url);
	}
	
	private void connect(String url) {
		log.interesting("Attempting to connect", parent.getLogContext());
		final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
//		
//		ClientManager client = ClientManager.createClient();
		
		WebSocketContainer c = ContainerProvider.getWebSocketContainer();
		c.setDefaultMaxTextMessageBufferSize(1024 * 1024);
		try {
			c.connectToServer(this.hce, cec, new URI(url));
			// Wait for the endpoint to call us on success or failure.
		} catch (DeploymentException | IOException | URISyntaxException e) {
			errorOccurred(null);
		}		

	}

	
	private void changeState(WrapperState ws) {
		synchronized(lock) {
			
			if(ws == WrapperState.ACTIVE) {
				isInitialConnect_synch_lock = false;
			}
			
			log.info("Session wrapper state for "+parent.getUuid()+" is now "+ws.name(), parent.getLogContext());
			state_synch_lock = ws;
		}
	}
	
	/** This should be called by the endpoint */
	public void newSession(Session session) {
		if(disposed) {
			log.err("Attempt to add session on disposed wrapper", parent.getLogContext());
			return;
		}
		
		ObjectMapper om = new ObjectMapper();
		JsonClientConnect ccj = new JsonClientConnect();
		ccj.setClientVersion("1.0");
		
		ccj.setUsername(parent.getUsername());
		ccj.setPassword(parent.getPassword());
		ccj.setUuid(parent.getUuid());
		ccj.setLastActionResponseReceived(parent.getNextSynchronousMessageId()-1);
		
		ccj.setRoundToEnter(parent.getCurrentRound());
				
		synchronized (lock) {
			
			ccj.setInitialConnect(isInitialConnect_synch_lock);
			try {
				
				changeState(WrapperState.WAITING_FOR_CONFIRMATION);
				
				session_synch_lock = session;
				basicRemote_synch_lock = session.getBasicRemote();
				basicRemote_synch_lock.sendBinary(CompressionUtils.compressToByteBuffer(om.writeValueAsString(ccj)));
								
//				basicRemote_synch_lock.sendText(om.writeValueAsString(ccj));
				
			} catch (IOException e) {
				e.printStackTrace();
				errorOccurred(session);
			}
		}
	}
	
	public void onDisconnect(Session session) {
		if(session == null) { return; }
				
		boolean match = false;
		synchronized(lock) {
			if(state_synch_lock == WrapperState.COMPLETE) {
				return;
			}

			if(session == session_synch_lock) {
				match = true;
			}
		}
		
		if(match) {
			errorOccurred(session);			
		}
	}
	
	private void waitingForRoundToStart(Session failedSession) {
		boolean startThread = false;
		
		// Close failed session on a separate thread.
		new Thread() {
			public void run() {
				try {
					failedSession.close();
				} catch(Exception e) {
					/* ignore*/
				}
			};
		}.start();

		// Only attempt to restart a session once (to prevent multiple concurrent attempts)
		synchronized (sessionRestartedNew_synch) {
			if(sessionRestartedNew_synch.containsKey(failedSession.getId())) {
				startThread = false;
			} else {
				sessionRestartedNew_synch.put(failedSession.getId(), new OldSessionInfo(System.nanoTime()));
				startThread = true;
			}
			clearOldSessionsIfNeeded();
		}

		if(startThread) {
			// Start a new thread so we aren't blocking the calling thread
			new Thread() {
				@Override
				public void run() {
					changeState(WrapperState.INITIAL);
					try {
						// Wait 10 seconds, since we are waiting for the round to start.
						Thread.sleep(10 * 1000);
					} catch (InterruptedException e) {
						/* ignore*/
						e.printStackTrace();
					}
					String url;
					synchronized(lock) {
						url = url_synch_lock;
					}
					connect(url);
				}
			}.start();
		}
	}
	
	private void clearOldSessionsIfNeeded() {
		synchronized (sessionRestartedNew_synch) {
			if(sessionRestartedNew_synch.size() > 200) {
				// Expire session restoration entries that are older than 10 minutes.
				long expireTimeInNanos = System.nanoTime()-TimeUnit.NANOSECONDS.convert(10, TimeUnit.MINUTES);
				
				for(Iterator<Entry<String, OldSessionInfo>> it = sessionRestartedNew_synch.entrySet().iterator(); it.hasNext();) {
					if(it.next().getValue().infoCreationTimeInNanos < expireTimeInNanos) {
						it.remove();
					}
				}
				
			}
		}
	}
	
	private void errorOccurred(Session failedSession) {
		// failSession should only be null when connect() fails..
		
//		if(failedSession == null) { return; }
		if(disposed == true) { return; }
		
		boolean startThread = false;
		
		// Close failed session on a separate thread.
		if(failedSession != null) {
			new Thread() {
				public void run() {
					try {
						failedSession.close();
					} catch(Exception e) {
						/* ignore*/
					}
				};
			}.start();
		}
		
		
		final long localTimeToWaitInMsecs = timeToWaitBetweenConnectFailuresInMsecs;
				
		timeToWaitBetweenConnectFailuresInMsecs *= 2;
		if(timeToWaitBetweenConnectFailuresInMsecs > MAX_TIME_TO_WAIT_BETWEEN_CONN_FAILURES) {
			timeToWaitBetweenConnectFailuresInMsecs = MAX_TIME_TO_WAIT_BETWEEN_CONN_FAILURES;
		}
		
		// Only attempt to restart a session once (to prevent multiple concurrent attempts)
		if(failedSession != null) {
			synchronized (sessionRestartedNew_synch) {
				if(sessionRestartedNew_synch.containsKey(failedSession.getId())) {
					startThread = false;
				} else {
					sessionRestartedNew_synch.put(failedSession.getId(), new OldSessionInfo(System.nanoTime()));
					startThread = true;
				}
				clearOldSessionsIfNeeded(); 
			}
		} else {
			startThread = true;
		}
		
		if(startThread) {
			// Start a new thread so we aren't blocking the calling thread
			new Thread() {
				@Override
				public void run() {
					changeState(WrapperState.INITIAL);
					try {
						Thread.sleep(localTimeToWaitInMsecs);
					} catch (InterruptedException e) {
						/* ignore */
						e.printStackTrace();
					}
					String url;
					synchronized(lock) {
						url = url_synch_lock;
					}
					connect(url);
				}
			}.start();
		}
	}
	
	public void write(String str) {
		if(disposed) {
			log.err("Attempt to write to disposed wrapper.", parent.getLogContext());
			return;
		}
		
		if(latencySim != null) {
			latencySim.addMessage(thread, str);
		} else {
			thread.addMessageToSend(str);	
		}
		
		
	}
	
	// Called by endpoint message handler
	public void receiveJson(String str, Session session) {
		
		ObjectMapper om = new ObjectMapper();
		try {
			String messageType = (String) om.readValue(str, Map.class).get("type");
			
			Class<?> c = JsonMessageMap.getMap().get(messageType);

			Object o = om.readValue(str, c);
			
			if(messageType.equals(JsonClientConnectResponse.TYPE)) {
				JsonClientConnectResponse jccr = (JsonClientConnectResponse)o;
				
				if(jccr.getConnectResult().equals(ConnectResult.SUCCESS.name())) {
					parent.setCurrentRound(jccr.getRoundEntered());
					changeState(WrapperState.WAITING_FOR_RESEND);
				} else if(jccr.getConnectResult().equals(ConnectResult.FAIL_ROUND_NOT_STARTED.name())) {
					System.out.println("Update: Round has not yet started, waiting to start.");
					waitingForRoundToStart(session);
				} else if(jccr.getConnectResult().equals(ConnectResult.FAIL_ROUND_OVER.name())) {
					System.out.println("Update: Round is over, waiting for new round..");
					changeState(WrapperState.COMPLETE);
				} else {
					errorOccurred(session);
				}
				
			} else {
				parent.getMessageReceiver().receiveJson(messageType, o, str, session, parent);
			}
				
		} catch (Exception e) {
			e.printStackTrace();
			log.severe("Exception on receive json: "+str, e, null);
		}
		
	}


	@Override
	public void dispose() {
		
		disposed = true;
		
		thread.interrupt();
		
		synchronized(sessionRestartedNew_synch) {
			sessionRestartedNew_synch.clear();
		}
		
		try {
			session_synch_lock.close(); // Intentional non-synch call
		} catch (IOException e) {
			/* ignore */
		}
		
		thread.dispose();
		
		hce.dispose();
		
	}
	
	
	private class ClientStateOutputThread extends Thread implements ILatencySimReceiver {

		private final Object threadLock = new Object();
		
		private final List<String> messagesToSend_synch_threadLock = new ArrayList<>();
		
		private final List<String> last100Messages_synch_threadLock = new ArrayList<String>();
		
		public ClientStateOutputThread() {
			super(ClientStateOutputThread.class.getName());
		}
		
		@Override
		public void run() {
			
			try {
				innerRun();
			} catch (InterruptedException e) {
				if(disposed) {
					/* This is expected on dispose. */
					log.info("LSW client interrupted.", parent.getLogContext());
				} else {
					log.severe("Unexpected interrupt", e, parent.getLogContext());
				}
			} finally {
				dispose();
			}
		}
		
		private void innerRun() throws InterruptedException {
			List<String> localCopy = new ArrayList<>();
			List<String> localLast100Messages = new ArrayList<>();

			
			while(!disposed && !parent.isRoundComplete()) {

				boolean isActive = false;
				// Wait for messages to send (or 100 msecs).
				synchronized (threadLock) {
					if(messagesToSend_synch_threadLock.size() == 0) {
						threadLock.wait(100);
					} else {
						// If the backing session is active, then try to write the messages
						synchronized(LibertySessionWrapper.this.lock) {
							if(state_synch_lock == WrapperState.ACTIVE ) {
								isActive = true;
							}
						}
						if(isActive) {
							localCopy.addAll(messagesToSend_synch_threadLock);
							messagesToSend_synch_threadLock.clear();
						}
						
					}
				}
				
				if(!isActive && localCopy.size() == 0) {
					boolean wait = false;
					synchronized(LibertySessionWrapper.this.lock) {
						if(state_synch_lock == WrapperState.WAITING_FOR_RESEND) {
							wait  = false;
						}
					}
					if(wait) {
						Thread.sleep(50);
					}
				}
				
				localLast100Messages.clear();				
				if(state_synch_lock == WrapperState.WAITING_FOR_RESEND) {
					synchronized(threadLock) {
						localLast100Messages.addAll(last100Messages_synch_threadLock);
					}
				}
				
				synchronized(LibertySessionWrapper.this.lock) {

					if(state_synch_lock == WrapperState.WAITING_FOR_RESEND) {
						
						if(session_synch_lock.isOpen()) {
							for(String previousMessage : localLast100Messages) {
								if(Logger.CLIENT_SENT) { log.info("Sending previous text: "+previousMessage, parent.getLogContext()); }
								if(NG.ENABLED) { NG.log(RCRuntime.GAME_TICKS.get(), "Sending previous text:" +previousMessage); }
								try {
									basicRemote_synch_lock.sendBinary(CompressionUtils.compressToByteBuffer(previousMessage));
//									basicRemote_synch_lock.sendText(previousMessage);
								} catch (IOException e) {
									log.err("Errored occured on writing previous text", e, parent.getLogContext());
									e.printStackTrace();
									errorOccurred(session_synch_lock);
									break;
								}
							}
							
							// We have successfully resent the previous message
							changeState(WrapperState.ACTIVE);
							
							timeToWaitBetweenConnectFailuresInMsecs = INITIAL_TIME_TO_WAIT_BETWEEN_CONN_FAILURES;
							
							
						} else {
							errorOccurred(session_synch_lock);
						}
						
					}
					
					outer: for(String str : localCopy) {
						
						if(RCRuntime.SIMULATE_BAD_CONNECTION) {
							if(Math.random() < .1) {
								try {
									if(NG.ENABLED) { NG.log(RCRuntime.GAME_TICKS.get(), "Nuking connection!"); }
									session_synch_lock.close();
								} catch (IOException e) { /* ignore*/
								}
							}
						}
						
						if(session_synch_lock == null) {
							break outer;
						}
													
						if(state_synch_lock == WrapperState.ACTIVE ) {
							
							 if(session_synch_lock.isOpen()) {
								try {
									if(Logger.CLIENT_SENT) { log.info("Sending current text: "+str, parent.getLogContext()); }										
									
									if(NG.ENABLED) { NG.log(RCRuntime.GAME_TICKS.get(), "Sending current text:" +str); }
									
									basicRemote_synch_lock.sendBinary(CompressionUtils.compressToByteBuffer(str));
//									basicRemote_synch_lock.sendText(str);
								} catch (IOException e) {
									log.err("Exception occurred on writing text", e, parent.getLogContext());
									errorOccurred(session_synch_lock);
									break outer;
								}
							 }  else {
								 errorOccurred(session_synch_lock);
								 break outer;
							 }
						}
																	
					} // end outer
										
				}  // end synchronized
				
				localCopy.clear();
				
			}
		}
		
		public void addMessageToSend(String str) {
			if(disposed) {
				return;
			}
			
			synchronized (threadLock) {
				last100Messages_synch_threadLock.add(str);
				if(last100Messages_synch_threadLock.size() > 100) {
					last100Messages_synch_threadLock.remove(0);
				}
				messagesToSend_synch_threadLock.add(str);
				threadLock.notify();
			}
		}

		public void dispose() {
			
			synchronized(threadLock) {
				messagesToSend_synch_threadLock.clear();
				last100Messages_synch_threadLock.clear();
			}			
		}

	}

	
	static class OldSessionInfo {
		final long infoCreationTimeInNanos;
		
		public OldSessionInfo(long infoCreationTimeInNanos) {

			this.infoCreationTimeInNanos = infoCreationTimeInNanos;
		}
		
		public long getInfoCreationTimeInNanos() {
			return infoCreationTimeInNanos;
		}
	}


	@Override
	public boolean isDisposed() {
		return disposed;
	}

	
	
//	@Override
//	public boolean isExpired() {
//		return System.nanoTime() >= expirationTimeInNanos;
//	}
//
//	@Override
//	public boolean allowReplace(IManagedResource resource) {
//		return false;
//	}
//
//	@Override
//	public String getId() {
//		synchronized(session_synch_lock) {
//			return session_synch_lock.getId();
//		}
//	}

//	@Override
//	public boolean equals(Object obj) {
//		if(!(obj instanceof LibertySessionWrapper)) {
//			return false;
//		}
//		
//		LibertySessionWrapper lsw = (LibertySessionWrapper)obj;
//		
//		synchronized (lock) {
//			synchronized(lsw.lock) {
//				return lsw.session_synch_lock.getId().equals(session_synch_lock.getId());				
//			}
//		}
//	}
}
