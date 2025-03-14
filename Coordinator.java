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

        // Start worker threads (10 of them)
        for (long i = 1; i < 10; i++) {
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
                            System.out.println("Command received from client: " + key + " " + command);
                            
                            // Process register
                            if (command.startsWith("register")) {
                                // register <portNumber>  <clientProvidedID>
                                String[] parts = command.split(" ");
                                registerClient(Long.parseLong(parts[2]), Integer.parseInt(parts[1]));
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

    // Placeholder for handling client registration
    public void registerClient(long clientProvidedId, int port) throws IOException{
        // Find assigned coordinator ID
        Long assignedId = clientIdMap.get(clientProvidedId);

        Client client = clientMap.get(assignedId);
        try (ServerSocket messageServerSocket = new ServerSocket(port)) {
            // Send message to client that youre ready to accept socket
            client.commandDataOut.writeUTF("OK");
            client.commandDataOut.flush();
            
            Socket messageSocket = messageServerSocket.accept();
            
            client.setConnected(true);
            client.lastMsgReceived = System.currentTimeMillis();
            client.messageSocket = messageSocket;
            client.messageDataIn = new DataInputStream(messageSocket.getInputStream());
            client.messageDataOut = new DataOutputStream(messageSocket.getOutputStream());
        }
    }

    // Placeholder for handling client deregistration
    public void deregisterClient(long clientProvidedId) {
        Long assignedId = clientIdMap.remove(clientProvidedId);
        if (assignedId != null) {
            clientMap.remove(assignedId);
        }
    }

    // Placeholder for handling client disconnection
    public void disconnectClient(long clientProvidedId) {
        Long assignedId = clientIdMap.get(clientProvidedId);
        if (assignedId != null) {
            clientMap.get(assignedId).setConnected(false);
        }
    }

    // Placeholder for handling client reconnection
    public void reconnectClient(long clientProvidedId, String ipAddress, int port) {
        Long assignedId = clientIdMap.get(clientProvidedId);
        if (assignedId != null) {
            clientMap.get(assignedId).setConnected(true);
            // Messages will be retrieved from the queue when needed
        }
    }

    // Placeholder for handling message multicast
    public void multicastMessage(long clientProvidedId, String message) {
        
        Message msg = new Message(message, System.currentTimeMillis());

        Long assignedId = clientIdMap.get(clientProvidedId);
        if (assignedId != null) {
            messageQueue.add(msg); // Add to the global message queue
        }
    }

    private Boolean isStaleMessage(Message message) {
        return (System.currentTimeMillis() - this.T_d) > message.timestamp;
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
            this.lastMsgReceived = System.currentTimeMillis();

            this.commandSocket = commandSocket;
            this.commandDataIn = new DataInputStream(commandSocket.getInputStream());
            this.commandDataOut = new DataOutputStream(commandSocket.getOutputStream());
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
