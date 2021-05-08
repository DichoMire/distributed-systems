import java.io.*;
import java.net.*;
class TCPReceiver{
public static void main(String [] args){
try{
ServerSocket ss = new ServerSocket(4322);
for(;;){
try{
Socket client = ss.accept();
BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
String line;
while((line = in.readLine()) != null)
System.out.println(line+" received");
client.close();
}
catch(Exception e){System.out.println("error "+e);}}}catch(Exception e){System.out.println("error "+e);}}}