import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
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
    boolean isRegistered;

    public void run(String configFile) {

        // Parse Config File
        String ipAndPortNumber;
        try (Scanner scanner = new Scanner( new File(configFile) )) {
            ID = Long.parseLong(scanner.nextLine());
            messageFile = scanner.nextLine();
            ipAndPortNumber = scanner.nextLine();
            isRegistered = false;
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
                        handleRegister(command, ipAndPort[0]);
                        commandDataIn.readUTF();
                        break;
                    case ("deregister"):
                        if (isRegistered) {
                            handleDeregister(command);
                            commandDataIn.readUTF();
                        } else {
                            System.out.println("Need to be registered to deregister");
                        }
                        break;
                    case ("disconnect"):
                        if (isRegistered) {
                            handleDeregister(command);
                            commandDataIn.readUTF();  
                        } else {
                            System.out.println("Need to be registered to disconnect");
                        }
                        break;
                    case ("reconnect"):
                        if (isRegistered) {
                            handleReconnect(command, ipAndPortNumber);
                            commandDataIn.readUTF();
                        } else {
                            System.out.println("Need to registered before reconnecteding");
                        }
                        break;
                    case ("msend"):
                        System.out.println("ERROR: msend not implemented");
                        break;
                    default:
                        // TODO: Delete sending this command to coordinator
                        commandDataOut.writeUTF(command + " " + String.valueOf(ID));
                        commandDataOut.flush();
                        commandDataIn.readUTF();
                        System.out.println("ERROR: Invalid command");
                        break;
                }
            }

        } catch (Exception e) {
            System.out.println("Error reading from System.in" + e.toString());
            return;
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

            // Await awknoledgement from coordinator
            commandDataIn.readUTF();

            // Start a new thread to handle messages
            messageThread = new Thread ( new MessageHandler(command, ip, messageFile));
            messageThread.start();
            isRegistered = true;

        } catch (IOException e) {
            System.out.println("Error registering participant with coordinator: " + e.toString());
        }
    }

    private void handleDeregister(String command) {
        try {
            // Send commmand to coordinator
            commandDataOut.writeUTF(command + " " + String.valueOf(ID));
            commandDataOut.flush();

            // Delete the messageHandler thread
            messageThread.interrupt();
            messageThread = null;

            // Await awknoledgement from coordinator
            commandDataIn.readUTF();
            isRegistered = false;
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
    private DataOutputStream messageDataOut; // TODO: Do we need this?
    private String messageLogsFile;

    public MessageHandler(String command, String ip, String messageLogsFile) throws IOException{
        String[] parts = command.split(" ");
        this.messageLogsFile = messageLogsFile;
        this.messageSocket = new Socket(ip, Integer.parseInt(parts[1]));
        this.messageDataIn = new DataInputStream(this.messageSocket.getInputStream());
        this.messageDataOut = new DataOutputStream(this.messageSocket.getOutputStream());
    }

    @Override
    public void run() {
        File file = new File(messageLogsFile); // Create file object

        // Listen for new messages and write to file
        while (true) { 
            try {
                String message = messageDataIn.readUTF();
                System.out.println("[DEBUG] Message Received: " + message);   
            } catch (IOException e) {
            }
        }
    }

}