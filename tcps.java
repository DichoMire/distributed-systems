import java.io.*;
import java.net.*;
class TCPSender{
public static void main(String [] args){
try{
//InetAddress address =InetAddress.getLocalHost();
Socket socket = new Socket("192.168.1.249",4322);
PrintWriter out = new PrintWriter(socket.getOutputStream());
for(int i=0;i<10;i++){
out.println("TCP message "+i); out.flush();
System.out.println("TCP message "+i+" sent");
Thread.sleep(1000);
}}catch(Exception e){System.out.println("error"+e);}}}