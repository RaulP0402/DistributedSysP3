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
    Socket commandSocket, messageSocket;
    DataInputStream commandDataIn, messageDataIn;
    DataOutputStream commandDataOut, messageDataOut;

    public void run(String configFile) {

        // Parse Config File
        String ipAndPortNumber;
        try (Scanner scanner = new Scanner( new File(configFile) )) {
            ID = Long.parseLong(scanner.nextLine());
            messageFile = scanner.nextLine();
            ipAndPortNumber = scanner.nextLine();
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


        try (Scanner scanner = new Scanner(System.in)) {
            String command = "";
            while (!command.equals("exit")) {
                System.out.print("myParticipant> ");
                command = scanner.nextLine();

                if (command.startsWith("register")) {
                    handleRegister(command, ipAndPort[0]);
                } else {
                    commandDataOut.writeUTF(command + " " + String.valueOf(ID));
                    commandDataOut.flush();
                }

                // Get awknowledgement from coordinator 
                commandDataIn.readUTF();
            }

        } catch (Exception e) {
            System.out.println("Error reading from System.in" + e.toString());
            return;
        }
    }

    public void handleRegister(String command, String ip) {
        try {
            // Send command to coordinator
            commandDataOut.writeUTF(command + " " + String.valueOf(ID));
            commandDataOut.flush();
            // Await resul t from coordinator
            commandDataIn.readUTF();

            String[] parts = command.split(" ");
            messageSocket = new Socket(ip, Integer.parseInt(parts[1]));
            messageDataIn = new DataInputStream( messageSocket.getInputStream() );
            messageDataOut = new DataOutputStream( messageSocket.getOutputStream() );
        } catch (IOException e) {
            System.out.println("Error registering participant with coordinator: " + e.toString());
        }
    }

    public static void main(String[] args) {
        Participant participant = new Participant();
        participant.run(args[0]);
    }

}