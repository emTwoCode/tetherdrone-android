package com.zuehlke.tetherdrone;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

public class MainActivity extends Activity {

    private static final String DRONE_COMMANDS_URL = "http://tetherdrone.cloudapp.net/drone/commands.ashx";
    private static final String DRONE_GPS_URL = "http://tetherdrone.cloudapp.net/drone/gps.ashx";

    private static final String AT_REF_COMMAND_TAKEOFF = "290718208";
    private static final String AT_REF_COMMAND_LAND = "290717696";
    private static final String AT_REF_COMMAND_EMERGENCY = "290717952";
    private static final String AT_REF_COMMAND_RESET = "290717696";

    private static final String AT_PCMD_COMMAND_HOVER = "0,0,0,0,0";

    private static final String AT_PCMD_COMMAND_UP = "1,0,0,1048576000,0";
    private static final String AT_PCMD_COMMAND_DOWN = "1,0,0,-1098907648,0";

    //private static final String AT_PCMD_COMMAND_FORWARD = "1,0,-1119040307,0,0"; // -0.05
    //private static final String AT_PCMD_COMMAND_BACKWARD = "1,0,1028443341,0,0"; // 0.05

    private static final String AT_PCMD_COMMAND_FORWARD = "1,0,-1098907648,0,0"; // -0.25
    private static final String AT_PCMD_COMMAND_BACKWARD = "1,0,1048576000,0,0"; // 0.25

    //private static final String AT_PCMD_COMMAND_FORWARD = "1,0,-1090519040,0,0"; // -0.5
    //private static final String AT_PCMD_COMMAND_BACKWARD = "1,0,1056964608,0,0"; // 0.5

    private static final String AT_PCMD_COMMAND_LEFT = "1,-1119040307,0,0,0";
    private static final String AT_PCMD_COMMAND_RIGHT = "1,1028443341,0,0,0";

    private static final String AT_PCMD_COMMAND_TURNLEFT = "1,0,0,0,-1098907648"; // fixed
    private static final String AT_PCMD_COMMAND_TURNRIGHT = "1,0,0,0,1048576000"; // fixed

    TextView textViewLog;
    TextView textViewStatus;

    InetAddress lastDroneIp;

    int nextSeqNummer = 1;

    private Location lastLocation;

    private CancelableThread keepAliveThread;
    private CancelableThread receiveCommandsFromNetThread;
    private CancelableThread gpsUploaderThread;
    private CancelableThread receiveNavDataFromDroneThread;


    public class FindDroneAsyncTask extends AsyncTask<Object, String, InetAddress> {

        @Override
        protected InetAddress doInBackground(Object... params) {

            InetAddress address = null;

            for (int last = 1; last < 128; last++) {

                String bottom = "192.168.1." + last;
                address = checkIp(bottom);

                if (address != null) {
                    return address;
                }

                String top = "192.168.1." + (255-last);
                address = checkIp(top);

                if (address != null) {
                    return address;
                }
            }

            return null;
        }

        private InetAddress checkIp(String ipAdress) {
            String ip = ipAdress;

            //String ip = "www.heise.de";
            int port = 23;

            this.publishProgress("Find Drone trying: " + ip + ":"+ port);

            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 50);

                this.publishProgress("Found telnet server on '" + ip + "'");

                return socket.getInetAddress();

            }
            catch (Exception e) {
                // addLog(e.toString());
                // this.publishProgress("Cannot connect to ip." + e.toString());

                return null;
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            addLog(values[0]);
        }

        @Override
        protected void onPostExecute(InetAddress inetAddress) {

            if (inetAddress != null) {

                addLog("Found drone on: " + inetAddress.toString());

                MainActivity.this.lastDroneIp = inetAddress;

                if (MainActivity.this.keepAliveThread != null) {
                    addLog("Stopping KeepAlive");
                    MainActivity.this.keepAliveThread.cancel();
                }

                if (MainActivity.this.receiveCommandsFromNetThread != null) {
                    addLog("Stopping CommandRcv Thread");
                    MainActivity.this.receiveCommandsFromNetThread.cancel();
                }

                if (MainActivity.this.gpsUploaderThread != null) {
                    addLog("Stopping GPS Upload Thread");
                    MainActivity.this.gpsUploaderThread.cancel();
                }

                if (MainActivity.this.receiveNavDataFromDroneThread != null) {
                    addLog("Stopping NavData Thread");
                    MainActivity.this.receiveNavDataFromDroneThread.cancel();
                }

                addLog("Starting keep alive and other threads.");

                MainActivity.this.keepAliveThread = new KeepAliveThread();
                keepAliveThread.start();

                MainActivity.this.receiveCommandsFromNetThread = new ReceiveCommandsFromNetRunner();
                receiveCommandsFromNetThread.start();

                MainActivity.this.gpsUploaderThread = new SendLocationToNetRunner();
                gpsUploaderThread.start();

                MainActivity.this.receiveNavDataFromDroneThread = new RecieveNavDataFromDrone();
                receiveNavDataFromDroneThread.start();
            }
            else {
                addLog("Not found any drone");
            }
        }
    }

    public class KeepAliveThread extends CancelableThread {
        @Override
        public void run() {

            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();

                while(!this.shouldStop) {

                    if (MainActivity.this.nextSeqNummer > Integer.MAX_VALUE - 100) {
                        addLog("Reached MaxValue, resetting counter");
                        MainActivity.this.nextSeqNummer = 0;
                    }

                    String initMessage = "AT*COMWDG=" + (MainActivity.this.nextSeqNummer++) + "\n";
                    sendMessage(socket, initMessage);

                    Thread.sleep(40);
                }

            } catch (SocketException e) {
                e.printStackTrace();

                this.publishProgress("SocketException in KeepAlive: " + e.toString());
            } catch (IOException e) {
                e.printStackTrace();

                this.publishProgress("IOException in KeepAlive: " + e.toString());
            } catch (InterruptedException e) {
                e.printStackTrace();
                this.publishProgress("InterruptedException in KeepAlive: " + e.toString());
            }
        }


        protected void publishProgress(String... values) {
            MainActivity.this.addLog(values[0]);
        }
    }

    public class ReceiveCommandsFromNetRunner extends CancelableThread {

        @Override
        public void run() {

            addLog("ReceiveCommandsFromNet has started");

            while(!this.shouldStop) {

                InputStream content = null;
                try {
                    HttpClient httpclient = new DefaultHttpClient();
                    HttpResponse response = httpclient.execute(new HttpGet(DRONE_COMMANDS_URL));

                    content = response.getEntity().getContent();

                    BufferedReader r = new BufferedReader(new InputStreamReader(content));
                    String line;

                    while ((line = r.readLine()) != null) {
                        this.dispatchCommand(line);
                    }

                } catch (Exception e) {
                    this.dispatchCommand("hover");
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }


        protected void dispatchCommand(String... values) {
            for(String value : values) {

                addLog("processing command '" + value + "'");

                if (value.toLowerCase().equals("takeoff")) { MainActivity.this.sendRefCommand(AT_REF_COMMAND_TAKEOFF); }
                if (value.toLowerCase().equals("land")) { MainActivity.this.sendRefCommand(AT_REF_COMMAND_LAND); }

                if (value.toLowerCase().equals("reset")) { MainActivity.this.sendRefCommand(AT_REF_COMMAND_RESET); }
                if (value.toLowerCase().equals("emergency")) { MainActivity.this.sendRefCommand(AT_REF_COMMAND_EMERGENCY); }

                if (value.toLowerCase().equals("hover")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_HOVER); }

                if (value.toLowerCase().equals("up")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_UP); }
                if (value.toLowerCase().equals("down")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_DOWN); }

                if (value.toLowerCase().equals("forward")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_FORWARD); }
                if (value.toLowerCase().equals("back")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_BACKWARD); }

                if (value.toLowerCase().equals("turnleft")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_TURNLEFT); }
                if (value.toLowerCase().equals("turnright")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_TURNRIGHT); }
                if (value.toLowerCase().equals("left")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_RIGHT); }
                if (value.toLowerCase().equals("right")) { MainActivity.this.sendPCMDCommand(AT_PCMD_COMMAND_LEFT); }

            }
        }
    }

    public class SendLocationToNetRunner extends CancelableThread {

        @Override
        public void run() {

            Location lastSentLocation = null;

            addLog("GPS Sender started..");

            while (!this.shouldStop) {

                try {

                    if (lastSentLocation != MainActivity.this.lastLocation) {

                        Location location = MainActivity.this.lastLocation;

                        JSONObject json = new JSONObject();

                        json.put("longitude", location.getLongitude());
                        json.put("latitude", location.getLatitude());
                        json.put("bearing", location.getBearing());
                        json.put("speed", location.getSpeed());
                        json.put("accuracy", location.getAccuracy());
                        json.put("altitude", location.getAltitude());
                        json.put("time", location.getTime());

                        HttpClient httpclient = new DefaultHttpClient();
                        HttpPost post = new HttpPost(DRONE_GPS_URL);

                        post.setEntity(new StringEntity(json.toString()));

                        HttpResponse response = httpclient.execute(post);

                        BufferedReader r = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

                        String line = r.readLine();

                        if (!line.equals("OK")) {
                            addLog("GPS Upload failed: " + line);
                        }
                        else {
                            addLog("GPS Location sent!");
                        }
                    }

                    lastSentLocation = MainActivity.this.lastLocation;

                } catch (JSONException e) {

                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public class RecieveNavDataFromDrone extends CancelableThread {

        DatagramSocket socket;
        private static final int MAX_PACKET_SIZE = 2048;

        @Override
        public void run() {

                try {
                    socket = new DatagramSocket(5554);

                    ticklePort(5554);

                    DatagramPacket packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);

                    while (!this.shouldStop) {
                        try {
                            socket.receive(packet);

                            addLog("Recieved a NavData Packet. Lenght: " + packet.getLength());

                            Thread.sleep(500);
                        }
                        catch (Exception e) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e1) {
                                e1.printStackTrace();
                            }
                        }
                    }


                } catch (SocketException e) {
                    e.printStackTrace();
                }

        }


        protected void ticklePort(int port) {
            byte[] buf = { 0x01, 0x00, 0x00, 0x00 };
            DatagramPacket packet = new DatagramPacket(buf, buf.length, MainActivity.this.lastDroneIp, port);
            try {
                if (socket != null) {
                    socket.send(packet);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                MainActivity.this.lastLocation = location;
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        });

        wireUiControls();
    }

    private void wireUiControls() {

        this.textViewLog = (TextView)this.findViewById(R.id.textViewLog);
        this.textViewStatus = (TextView)this.findViewById(R.id.textViewStatus);

        ((Button)this.findViewById(R.id.btnFind)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLog("Trying to find drone");

                try {

                    FindDroneAsyncTask task = new FindDroneAsyncTask();

                    task.execute();
                } catch (Exception e) {
                    addLog(e.toString());
                }
            }
        });

        ((Button)this.findViewById(R.id.btnStart)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
               MainActivity.this.sendRefCommand(AT_REF_COMMAND_TAKEOFF);
            }
        });


        ((Button)this.findViewById(R.id.btnLand)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.this.sendRefCommand(AT_REF_COMMAND_LAND);
           }
        });
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    public void addLog(String s) {

        String currentText = this.textViewLog.getText().toString();

        if(currentText.length() > 2048) {
            currentText = "Log cleared. Was more than 2048";
        }

        currentText = s + "\n" + currentText;

        final String newText = currentText;

        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
               textViewLog.setText(newText);
            }
        });
    }

    public void sendRefCommand(String atCommand)
    {
        final String message = "AT*REF=" + (nextSeqNummer++) + "," + atCommand;

        addLog("Sending '" + message + "'");

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {

                DatagramSocket socket = null;

                try {
                    socket = new DatagramSocket();

                    sendMessage(socket, message + "\r");

                } catch (IOException e) {
                    e.printStackTrace();

                    addLog("Cannot send REF '" + e.toString() + "'");
                }
            }
        });

        t.start();
    }

    public void sendPCMDCommand(String atCommand)
    {
        final String message = "AT*PCMD=" + (nextSeqNummer++) + "," + atCommand;

        addLog("Sending '" + message + "'");

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {

                DatagramSocket socket = null;

                try {
                    socket = new DatagramSocket();

                    sendMessage(socket, message + "\r");

                } catch (IOException e) {
                    e.printStackTrace();

                    addLog("Cannot send PCMD '" + e.toString() + "'");
                }
            }
        });

        t.start();
    }

    private void sendMessage(DatagramSocket socket, String message) throws IOException {
        byte[] data = message.getBytes("US-ASCII");

        DatagramPacket initPacket = new DatagramPacket(data, data.length, lastDroneIp, 5556);
        socket.send(initPacket);
    }
}
