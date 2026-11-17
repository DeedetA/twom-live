package fr.twomlive;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;

/**
 * VPN local limite a l'appli TWOM : tout le reste du telephone passe normalement.
 * Les connexions de TWOM sont relayees telles quelles vers les vrais serveurs ;
 * le flux du serveur de jeu est lu au passage pour etre decode. Rien n'est envoye au jeu.
 */
public class TwomVpnService extends VpnService {
    public static final String ACTION_START = "fr.twomlive.START";
    public static final String ACTION_STOP = "fr.twomlive.STOP";
    public static final String TWOM_PKG = "com.com2us.imo.normal.freefull.google.global.android.common";

    public static volatile boolean running = false;

    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private Relay relay;
    private Thread reader;
    private final Object writeLock = new Object();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    private synchronized void startVpn() {
        if (running) return;
        GameState gs = GameState.INSTANCE;
        SharedPreferences prefs = getSharedPreferences("twomlive", MODE_PRIVATE);
        int gamePort = prefs.getInt("port", 12210);

        Builder b = new Builder()
                .setSession("TWOM Live")
                .addAddress("10.111.222.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .setMtu(1500);
        try {
            b.addAllowedApplication(TWOM_PKG);      // seul TWOM passe par le VPN
        } catch (PackageManager.NameNotFoundException e) {
            gs.info("TWOM n'est pas installé sur ce téléphone.");
            stopSelf();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);

        tun = b.establish();
        if (tun == null) {
            gs.info("Impossible de démarrer le VPN (autorisation refusée ?).");
            stopSelf();
            return;
        }
        tunOut = new FileOutputStream(tun.getFileDescriptor());
        final FileInputStream in = new FileInputStream(tun.getFileDescriptor());

        relay = new Relay(packet -> {
            synchronized (writeLock) {
                try { tunOut.write(packet); } catch (IOException ignored) { }
            }
        }, new Relay.Protector() {
            @Override public boolean protect(Socket s) { return TwomVpnService.this.protect(s); }
            @Override public boolean protect(DatagramSocket s) { return TwomVpnService.this.protect(s); }
        }, gamePort, gs);

        running = true;
        reader = new Thread(() -> {
            byte[] buf = new byte[32767];
            try {
                while (running) {
                    int n = in.read(buf);
                    if (n > 0) relay.handle(buf, n);
                }
            } catch (IOException ignored) {
                // tun ferme
            }
        }, "tun-reader");
        reader.start();
        gs.info("Capture démarrée. Lance TWOM maintenant (ou relance-le s'il était déjà ouvert).");
    }

    private synchronized void stopVpn() {
        if (!running) return;
        running = false;
        if (relay != null) relay.stop();
        try { if (tun != null) tun.close(); } catch (IOException ignored) { }
        if (reader != null) reader.interrupt();
        relay = null;
        tun = null;
        GameState.INSTANCE.info("Capture arrêtée.");
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
