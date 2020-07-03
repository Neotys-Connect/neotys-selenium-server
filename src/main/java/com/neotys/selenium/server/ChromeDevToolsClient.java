package com.neotys.selenium.server;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import net.minidev.json.JSONObject;

import java.io.IOException;

public class ChromeDevToolsClient {
    /*public ChromeDevToolsClient(String host, int port) {
        waitCoordinator = new Object();
    }

    private WebSocketFactory ws;
    private Object waitCoordinator;

    private void sendWSMessage(String url, String message) throws IOException, WebSocketException, InterruptedException {
        JSONObject jsonObject = new JSONObject(message);
        final int messageId = jsonObject.getInt("id");
        if(ws==null){
            ws = new WebSocketFactory()
                    .createSocket(url)
                    .addListener(new WebSocketAdapter() {
                        @Override
                        public void onTextMessage(WebSocket ws, String message) {
                            System.out.println(message);
                            if(new JSONObject(message).getString("method").equals("Network.requestIntercepted   ")){
                                System.out.println("found");
                            }
                            if(new JSONObject(message).getInt("id")==messageId){
                                synchronized (waitCoordinator) {
                                    waitCoordinator.notifyAll();
                                }
                            }
                        }
                    })
                    .connect();
        }
        ws.sendText(message);
        synchronized (waitCoordinator) {
            waitCoordinator.wait(messageTimeoutInSecs*1000);
        }
    }*/
}
