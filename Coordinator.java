import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import static java.lang.System.exit;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class Coordinator {

    class Message {
        String message;
        long timestamp;
        Message(String message, long timestamp) {this.message = message; this.timestamp = timestamp;}
    };
    
    // Thread-safe mappings
    private ConcurrentHashMap<Long, Client> clientMap; // Maps coordinator-assigned clientId to Client object
    private ConcurrentHashMap<Long, Long> clientIdMap; // Maps client-provided ID to coordinator-assigned ID
    private ConcurrentLinkedQueue<Message> messageQueue; // Global message queue for multicast messages

    private AtomicLong nextClientId = new AtomicLong(0);

    private long T_d; // The time of which the coordinator should hold a message for
    private int portNumber; // Port number to listen on

    public Coordinator(String configFile) {
        clientMap = new ConcurrentHashMap<>();
        clientIdMap = new ConcurrentHashMap<>();
        messageQueue = new ConcurrentLinkedQueue<>();

        // Read Configuration file
        try (Scanner scanner = new Scanner( new File(configFile) )) {
           this.portNumber = scanner.nextInt();
           this.T_d = scanner.nextLong();
        } catch (FileNotFoundException e) {
            System.out.println("Error: Configuration file not found.");
            exit(1);
        }
    }

    public void run() {
        System.out.println("Coordinator running. Waiting for connections...");

        // Start worker threads (10 of them)
        for (long i = 0; i < 10; i++) {
            final long workerID = i;
            new Thread(() -> startWorkerThread(workerID)).start();
        }
        
        // Listen for any new connections, adding them to the clientMap & clientIdMap when doing so
        try (ServerSocket serverSocket = new ServerSocket(portNumber)) {
            while (true) { 
                Socket newParticipant = serverSocket.accept(); // Listen and accept on a new connections

                // Get the client side ID of the new participant
                DataInputStream dataIn = new DataInputStream( newParticipant.getInputStream() );
                long clientID = dataIn.readLong();

                // Assign a new coodinator ID to the client
                clientIdMap.put(clientID, nextClientId.incrementAndGet());
                System.out.println("New Client Connected: " + nextClientId);

                // Create client object and add to clientMap
                Client newClient = new Client(clientID, newParticipant);
                // Key: coordinator-assigned ID, Value: Client object
                clientMap.put(clientIdMap.get(clientID), newClient);

            }
        } catch (IOException e) {
            System.out.println("Error listening on port: " + portNumber + " " + e.getMessage());
            return;
        }
    }

    // Placeholder: Worker threads handle client communication
    // Worker thread listens for assigned clients
    // Initially, workers make sure the messageQueue is full of items [T_now - T_d, ..., T_now]
    // Handles messages for clients with (coordinatorId % 10 == workerId)
    // Process messages: registration, deregistration, disconnect, reconnect, multicast
    private void startWorkerThread(long workerID) {
        while (true) { 
            // While the queue is not empty and the message at the front is stale, remove it
            while (!messageQueue.isEmpty() && isStaleMessage(messageQueue.peek())) {
                messageQueue.poll();
            }

            // Handle all clients assigned to this worker (workerID % i) == 0
            for (long key: clientMap.keySet()) {
                if ((key % 10) == workerID && clientMap.containsKey(key)) {
                    Client client = clientMap.get(key);

                    try { 
                        // If data is available, read the command from the client
                        if (client.commandDataIn.available() > 0) {
                            String command = client.commandDataIn.readUTF();
                            System.out.println("Command received from client " + key + " : " + command);

                            String[] parts = command.split(" ");
                            switch (parts[0]) {
                                case ("register"):
                                    registerClient(Long.parseLong(parts[2]), Integer.parseInt(parts[1]));
                                    break;
                                case ("deregister"):
                                    deregisterClient(Long.parseLong(parts[1]));
                                    break;
                                case ("disconnect"):
                                    disconnectClient(Long.parseLong(parts[1]));
                                    break;
                                case("reconnect"):
                                    reconnectClient(Long.parseLong(parts[2]), Integer.parseInt(parts[1]));
                                    break;
                                case ("msend"):
                                    String[] message = command.split(" ", 2);
                                    multicastMessage(message[1]);
                                    break;
                                default:
                                    break;
                            }
                            
                            // Send awknoledgement to client that you processed command
                            client.commandDataOut.writeUTF("OK");

                        }
                    } catch (IOException e) {
                        System.out.println("Error reading from client: " + key + " " + e.getMessage());
                    }

                }
            }

        }
    }

    /*  Register Client
     *  Upon booting up the participant executable, every participant will connect to the coordinator and 
     *  be assigned a coordintor-assigned ID and have a client object created in the clientMap.
     *  The participant will then be able to send messages to the coordinator.
     * 
     * 1. Get the assigned coordinator ID of the client (this is assigned upon launching Participant.java) so it will always be present.
     * 2. Get the client object of the clientID (this is also assigned upon launching Participant.java).
     * 3. Start a serverSocket and await connection for the message socket from participant.
     * 4. Set connected to True, last message received is the current time, messageSocket is the socket received on the serverSocket.accpet().
     * 5. Create data streams for the message socket.
     */
    public void registerClient(long clientProvidedId, int port) throws IOException{
        // Find assigned coordinator ID
        Long assignedId = clientIdMap.get(clientProvidedId);

        Client client = clientMap.get(assignedId);
        // If client hasn't been registered yet, create a new message socket for it
        if (client.messageSocket == null) {
            try (ServerSocket messageServerSocket = new ServerSocket(port)) {
                // Send message to client that youre ready to accept socket
                client.commandDataOut.writeUTF("OK");
                client.commandDataOut.flush();
                
                Socket messageSocket = messageServerSocket.accept();
                
                client.setConnected(true);
                client.lastMsgReceived = getCurrentTimeInSeconds();
                client.messageSocket = messageSocket;
                client.messageDataIn = new DataInputStream(messageSocket.getInputStream());
                client.messageDataOut = new DataOutputStream(messageSocket.getOutputStream());
            }
        }
    }

    /*  Deregister client
        1. Get the coordinator-assigned ID of the client
        2. Get the client object of the client
        3. Set connected to false
        4. Close and set messageSocket to null
        5. Close data streams from message socket

        NOTE: We still maintain the client object in the client map and the coordianor-asssigned ID in the clientIdMap
        This is to ensure we still can read from commandSocket and process any future commands (i.e register).
     */
    public void deregisterClient(long clientProvidedId) {
        Long assignedID = clientIdMap.get(clientProvidedId);
        Client client = clientMap.get(assignedID);

        try {
            // Set connected to false
            client.setConnected(false);
            // Close and set messsae socket to null
            client.messageSocket.close();
            client.messageSocket = null;

            // Close data streams from message socket
            client.messageDataIn.close();
            client.messageDataOut.close();

            // Send awknoledgement to client
            client.commandDataOut.writeUTF("OK");
            client.commandDataOut.flush();
        } catch (IOException e) {
            System.out.println("Error deregistering client: " + clientProvidedId + " " + e.getMessage());
        }
    }

    /*  Disconnect client
     * 
     *  NOTE: This method works similar to deregister client. We set connected to false and close the message socket.
     */
    public void disconnectClient(long clientProvidedId) {
        Long assignedId = clientIdMap.get(clientProvidedId);
        Client client = clientMap.get(assignedId);
        try {
            client.setConnected(false);

            // Close message socket
            client.messageSocket.close();
            client.messageSocket = null;

            // Close data streams
            client.messageDataIn.close();
            client.messageDataOut.close();
            
            // Send awk back
            client.commandDataOut.writeUTF("OK");
            client.commandDataOut.flush();
        } catch (IOException e) {
            System.out.println("Error disconnecting client: " + clientProvidedId + " " + e.getMessage());
        }
    }

    /*  Reconnect client
     * 
     *  NOTE: This method works similar to register client. We get the portNumber from command and 
     *  create a new message socket for the client.
     */
    public void reconnectClient(long clientProvidedId, int port) {
        Long assignedId = clientIdMap.get(clientProvidedId);
        Client client = clientMap.get(assignedId);
        
        try (ServerSocket messageServerSocket = new ServerSocket(port)) {

            client.commandDataOut.writeUTF("OK");
            client.commandDataOut.flush();

            // Create new sockets
            Socket messageSocket = messageServerSocket.accept();

            // Set connected to true
            client.setConnected(true);
            
            // Assign message socket 
            client.messageSocket = messageSocket;

            // Assign data streams
            client.messageDataIn = new DataInputStream(messageSocket.getInputStream());
            client.messageDataOut = new DataOutputStream(messageSocket.getOutputStream());
            
            // Messages will be retrieved from the queue when needed
            for (Message nextMessage : messageQueue) {
                if (nextMessage.timestamp > client.lastMsgReceived) {
                    client.messageDataOut.writeUTF(nextMessage.message);
                    client.messageDataOut.flush();
                    client.lastMsgReceived = nextMessage.timestamp;
                }
            }

        } catch (IOException e) {
            System.out.println("Error reconnecting client: " + clientProvidedId + " " + e.getMessage());
        }
    }

    /*  Multicast message
     *  
     * NOTE: Here, we get the message from the client and add it to the global message queue. 
     * Then, we iterate through all the clients and if the client is currently connected then  we send the messsage
     * through theyr message socket.
     */
    public void multicastMessage(String msg) {
        
        // Create message object
        Message message = new Message(msg, getCurrentTimeInSeconds());
        // Add to message queue
        messageQueue.add(message);

        for (Client client: clientMap.values()) {
            if (client.isConnected) {
                try {
                    client.messageDataOut.writeUTF(message.message);
                    client.messageDataOut.flush();

                    client.lastMsgReceived = message.timestamp;   
                } catch (IOException e) {
                    System.out.println("Error sending multicast message.");
                }
            }
        }
        
    }

    /*
     * This method checks if the message is older than T_now - T_d
     */
    private Boolean isStaleMessage(Message message) {
        return (getCurrentTimeInSeconds() - this.T_d) > message.timestamp;
    }

    private Long getCurrentTimeInSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    // Inner class representing a client
    private static class Client {
        private long clientId, lastMsgReceived;
        private Socket commandSocket, messageSocket;
        private DataInputStream commandDataIn, messageDataIn;
        private DataOutputStream commandDataOut, messageDataOut;
        private boolean isConnected;

        public Client(long clientId, Socket commandSocket) throws IOException {
            this.clientId = clientId;
            this.isConnected = false; // Initially not connected
            this.lastMsgReceived = 0;

            this.commandSocket = commandSocket;
            this.commandDataIn = new DataInputStream(commandSocket.getInputStream());
            this.commandDataOut = new DataOutputStream(commandSocket.getOutputStream());

            this.messageSocket = null;
        }

        public void setConnected(boolean status) {
            this.isConnected = status;
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java Coordinator <config_file>");
            return;
        }

        Coordinator coordinator = new Coordinator(args[0]);
        coordinator.run();
    }
}
