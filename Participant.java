import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

class Participant {

    private long ID;
    private String messageFile;
    private Thread messageThread = null;
    Socket commandSocket;
    DataInputStream commandDataIn;
    DataOutputStream commandDataOut;
    boolean isRegistered, isConnected;

    public void run(String configFile) {

        // Parse Config File
        String ipAndPortNumber;
        try (Scanner scanner = new Scanner( new File(configFile) )) {
            ID = Long.parseLong(scanner.nextLine());
            messageFile = scanner.nextLine();
            ipAndPortNumber = scanner.nextLine();
            isRegistered = false;
            isConnected = false;
        } catch (FileNotFoundException e) {
            System.out.println("Error reading participant configuration file: " + configFile);
            return;
        }
        String[] ipAndPort = ipAndPortNumber.split(" ");

        // Connect to the Coordinator
        try {
            commandSocket = new Socket(ipAndPort[0], Integer.parseInt(ipAndPort[1]));  
            commandDataIn = new DataInputStream( commandSocket.getInputStream() );
            commandDataOut = new DataOutputStream( commandSocket.getOutputStream() );

            commandDataOut.writeLong(ID);
            commandDataOut.flush();

        } catch (IOException e) {
            System.out.println("Error creating socket for participant on IP: " + ipAndPort[0] + " and port: " + ipAndPort[1]);
            return;
        }


        // Read user commands from std input
        try (Scanner scanner = new Scanner(System.in)) {
            String command = "";
            while (!command.equals("exit")) {
                System.out.print("myParticipant> ");
                command = scanner.nextLine();
                
                String[] parts = command.split(" ");

                switch(parts[0]) {
                    case ("register"):
                        if (!isRegistered) {    
                            handleRegister(command, ipAndPort[0]);
                            commandDataIn.readUTF();
                            isConnected = true;
                        } else {
                            System.out.println("Participant is already registered.");
                        }
                        break;
                    case ("deregister"):
                        if (isRegistered) {
                            handleDeregister(command, false);
                            commandDataIn.readUTF();
                            isConnected = false;
                        } else {
                            System.out.println("Need to be registered to deregister");
                        }
                        break;
                    case ("disconnect"):
                        if (isRegistered && isConnected) {
                            handleDeregister(command, true);
                            commandDataIn.readUTF();  
                            isConnected = false;
                        } else {
                            if (!isRegistered) System.out.println("Need to be registered to disconnect");
                            else if (!isConnected) System.out.println("Need to be conncetd to disconnect");
                        }
                        break;
                    case ("reconnect"):
                        if (isRegistered && !isConnected) {
                            handleReconnect(command, ipAndPort[0]);
                            commandDataIn.readUTF();
                            isConnected = true;
                        } else {
                            if (!isRegistered) System.out.println("Need to registered before reconnecteding");
                            else if (isConnected) System.out.println("Participant is already connected.");
                        }
                        break;
                    case ("msend"):
                        if (isRegistered && isConnected) {
                            handleMulticastSend(command);
                            commandDataIn.readUTF();
                        } else {
                            if (!isRegistered) System.out.println("Must be registered before sending a message.");
                            else if (!isConnected) System.out.println("Must be connected before sending a message.");
                        }
                        break;
                    default:
                        System.out.println("ERROR: Invalid command");
                        break;
                }
            }

        } catch (Exception e) {
            System.out.println("Error reading from System.in" + e.toString());
            return;
        }

    }

    private void handleMulticastSend(String command) {
        try {
            commandDataOut.writeUTF(command);
            commandDataOut.flush();
        } catch (IOException e) {   
            System.out.println("Error sending msend " + e.toString());
        }
    }

    private void handleReconnect(String command, String ip) {
        try {
            // Send reconnect command to coordinator
            commandDataOut.writeUTF(command + " " + ID);
            commandDataOut.flush();

            // Await acknowledgment from coordinator
            commandDataIn.readUTF();

            // Restart the message listener thread
            messageThread = new Thread(new MessageHandler(command, ip, messageFile));
            messageThread.start();
        } catch (IOException e) {
            System.out.println("Error reconnecting participant: " + e.toString());
        }
    }

    private void handleRegister(String command, String ip) {
        try {
            // Create messageFile
            File file = new File(messageFile);
            file.createNewFile();

            // Send command to coordinator
            commandDataOut.writeUTF(command + " " + String.valueOf(ID));
            commandDataOut.flush();

            // Await acknowledgement from coordinator
            commandDataIn.readUTF();

            // Start a new thread to handle messages
            messageThread = new Thread ( new MessageHandler(command, ip, messageFile));
            messageThread.start();
            isRegistered = true;

        } catch (IOException e) {
            System.out.println("Error registering participant with coordinator: " + e.toString());
        }
    }

    private void handleDeregister(String command, boolean registered) {
        try {
            // Send command to coordinator
            commandDataOut.writeUTF(command + " " + String.valueOf(ID));
            commandDataOut.flush();

            // Delete the messageHandler thread
            messageThread.interrupt();
            messageThread = null;

            // On deregister, remove old messsages
            if (!registered) {
                // Delete messages 
                File file = new File(messageFile);
                file.delete();
            }

            // Await acknowledgement from coordinator
            commandDataIn.readUTF();
            isRegistered = registered;
        } catch (IOException e) {
            System.out.println("Error " + command + " participant with coordinator: " + e.toString());
        }
    }

    public static void main(String[] args) {
        Participant participant = new Participant();
        participant.run(args[0]);
    }

}

class MessageHandler implements Runnable {
    private Socket messageSocket;
    private DataInputStream messageDataIn;
    private String messageLogsFile;

    public MessageHandler(String command, String ip, String messageLogsFile) throws IOException{
        String[] parts = command.split(" ");
        this.messageLogsFile = messageLogsFile;
        this.messageSocket = new Socket(ip, Integer.parseInt(parts[1]));
        this.messageDataIn = new DataInputStream(this.messageSocket.getInputStream());
    }

    @Override
    public void run() {
        File file = new File(messageLogsFile); // Create file object

        // Listen for new messages and write to file
        while (true) { 
            try (FileWriter file_out = new FileWriter(file, true)) {
                String message = messageDataIn.readUTF();

                file_out.write(message + "\n");

            } catch (IOException e) {
            }
        }
    }

}