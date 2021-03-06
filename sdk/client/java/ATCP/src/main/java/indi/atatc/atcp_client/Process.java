package indi.atatc.atcp_client;

import com.google.gson.Gson;
import indi.atatc.arsa.ARSA;
import indi.atatc.atcp_client.packages.basics.Basics;
import indi.atatc.atcp_client.packages.data.ID;
import indi.atatc.atcp_client.packages.data.Values;
import indi.atatc.atcp_client.packages.log.Log;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;


class MessageQueue {
    public static class Message {
        public final ID.MID mid;
        public final String msg;
        public final Flags flags;

        public Message(ID.MID mid, String msg, Flags flags) {
            this.mid = mid;
            this.msg = msg;
            this.flags = flags;
        }
    }

    private final Basics.StructureClass.Queue<Message> messageQueue;

    public MessageQueue() {
        messageQueue = new Basics.StructureClass.Queue<>();
    }

    public int length() {
        return messageQueue.length();
    }

    public Message current() {
        return messageQueue.get();
    }

    public Message next() {
        messageQueue.remove();
        return messageQueue.get();
    }

    public ID.MID add(String msg, Flags flags) {
        ID.MID mid = ID.IDsManager.getInstance().newMID();
        Message message = new Message(mid, msg, flags);
        messageQueue.add(message);
        return mid;
    }
}

class Flag extends Basics.ContainerClass.Flag {
    private final boolean sys;

    public Flag(String flag, boolean sys) {
        super("");
        string = flag;
        this.sys = sys;
        if (sys) {
            this.flag = string;
        } else {
            this.flag = Base64.getEncoder().encodeToString(flag.getBytes());
        }
        value = null;
    }

    public Flag(String flag, String value, boolean sys) {
        super("");
        string = flag;
        this.sys = sys;
        if (sys) {
            this.flag = string;
        } else {
            this.flag = Base64.getEncoder().encodeToString(flag.getBytes());
        }
        this.value = value;
    }

    @Override
    public boolean isSys() {
        return sys;
    }

    static Basics.ContainerClass.Flag readFlag(String flag) {
        String[] flagInParas = Basics.TextClass.split(flag, ":");
        flag = flagInParas[0];
        boolean sys = flag.startsWith("\\") && flag.endsWith("\\");
        if (!sys) {
            flag = new String(Base64.getDecoder().decode(flag));
        }
        if (flagInParas.length > 1) {
            return new Flag(flag, flagInParas[1], sys);
        } else {
            return new Flag(flag, sys);
        }
    }
}

public abstract class Process {
    public interface ActionCallback {
        void onSend();

        void onSent();

        void onRecved(String result, Flags flags);
    }

    protected void onStart() {
    }

    protected void onStarted() {
        synchronized (client) {
            client.notify();
        }
    }

    protected void onSend(ID.MID mid) {
    }

    protected abstract void onSent(ID.MID mid);

    protected abstract void onRecved(ID.MID mid, String result, Flags flags);

    protected abstract String process(String msg, Flags flags);

    protected void onStop() {
    }

    protected void onStopped() {
    }

    protected void onDiscard() {
    }

    private static Process instance;
    public static Process getInstance() {
        return instance;
    }

    private final ID.PID pid = ID.IDsManager.getInstance().newPID();
    protected final Client client;
    private final Connection pConnection;
    protected final Values values = Values.getInstance();
    private String separatorSecondGrade = (String) values.get("separator_second_grade");
    private String separatorThirdGrade = (String) values.get("separator_third_grade");
    private boolean isAlive, hasStarted, hasStopped = false;
    boolean rsaOn = true;
    boolean messageBlockingOn = true;
    boolean multiThreadsOn = false;
    boolean errorHandlingOn = true;
    private final int keyLength;
    private ARSA.APrivateKey privateKey;
    private ARSA.APublicKey publicKey;
    public ARSA.APublicKey remotePublicKey;
    private String lastMessage = "";
    private final MessageQueue messageQueue = new MessageQueue();
    private final ConcurrentHashMap<ID.MID, ActionCallback> specificActionCallbacks = new ConcurrentHashMap<>();
    private boolean currentMessageIsSpecific;

    public final ID.PID getPID() {
        return pid;
    }

    public final Connection getConnection() {
        return pConnection;
    }

    public final void setRSAOn(boolean rsaOn) {
        if (!hasStarted) {
            this.rsaOn = rsaOn;
        }
    }

    public final void setMessageBlockingOn(boolean messageBlockingOn) {
        if (!hasStarted) {
            this.messageBlockingOn = messageBlockingOn;
        }
    }

    public final void setMultiThreadsOn(boolean multiThreadsOn) {
        if (!hasStarted) {
            this.multiThreadsOn = multiThreadsOn;
        }
    }

    public final void setErrorHandlingOn(boolean errorHandlingOn) {
        this.errorHandlingOn = errorHandlingOn;
    }

    public Process(Client client, Connection pConnection) {
        this.client = client;
        this.pConnection = pConnection;
        keyLength = (int) values.get("key_length");
    }

    public Process(Client client, Connection pConnection, int keyLength) {
        this.client = client;
        this.pConnection = pConnection;
        this.keyLength = keyLength;
    }

    public Process(Client client, Connection pConnection, ARSA.AKeyPair keyPair) {
        this.client = client;
        this.pConnection = pConnection;
        this.publicKey = keyPair.getPublicKey();
        this.privateKey = keyPair.getPrivateKey();
        keyLength = keyPair.getKeyLength();
    }

    private void  determineParameters() throws InvalidKeySpecException, NoSuchAlgorithmException {
        String msg;
        try {
            String info = "{\"name\": \"" + client.name + "\", \"type\": \"" + client.type + "\", \"project\": \"" + client.project + "\", \"process_id\": \"" + pid.toString() + "\"}";
            Basics.NetClass.send(pConnection.getSocket(), info + separatorSecondGrade + publicKey.toString() + separatorThirdGrade + publicKey.getKeyLength());
            msg = Basics.NetClass.recv(pConnection.getSocket());
        } catch (IOException e) {
            return;
        }
         String[] parameters = Basics.TextClass.split(msg, separatorSecondGrade);
        Gson gson = new Gson();
        Basics.ContainerClass.RemoteConfiguration remoteInfo = gson.fromJson(parameters[0], Basics.ContainerClass.RemoteConfiguration.class);
        if (remoteInfo.separator_second_grade != null) {
            separatorSecondGrade = remoteInfo.separator_second_grade;
        }
        if (remoteInfo.separator_third_grade != null) {
            separatorThirdGrade = remoteInfo.separator_third_grade;
        }
        if (rsaOn != remoteInfo.rsa) {
            if (errorHandlingOn) {
                stop();
            } else {
                throw new Basics.AccidentEvents.StatusError("unmatched peer configuration: rsa");
            }
        }
        if (messageBlockingOn != remoteInfo.message_blocking) {
            if (errorHandlingOn) {
                stop();
            } else {
                throw new Basics.AccidentEvents.StatusError("unmatched peer configuration: message_blocking");

            }
        }
        if (rsaOn) {
            String[] remotePublicKey = Basics.TextClass.split(parameters[1], "#");
            this.remotePublicKey = ARSA.APublicKey.importPublicKey(remotePublicKey[0], Integer.parseInt(remotePublicKey[1]));
        }
    }

    private void recvListener() {
        while (true) {
            String msg = null;
            try {
                msg = recv();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (msg == null) {
                return;
            }
            String[] msgInParas = Basics.TextClass.split(msg, separatorSecondGrade);
            msg = new String(Base64.getDecoder().decode(msgInParas[1]));
            Flags flags = new Flags(Basics.TextClass.split(msgInParas[0], separatorThirdGrade));

            if (flags.contains(Flags.KILL)) {
                send(new ID.MID("-1"), "", new Flags(Flags.KILL));
                return;
            }

            if (flags.contains(Flags.RESULT)) {
                ID.MID mid = messageQueue.current().mid;

                if (currentMessageIsSpecific) {
                    specificActionCallbacks.get(mid).onRecved(msg, flags.userFlags());
                    specificActionCallbacks.remove(mid);
                } else {
                    onRecved(mid, msg, flags.userFlags());
                }

                if (messageBlockingOn) {
                    MessageQueue.Message nextMsg = messageQueue.next();
                    if (null != nextMsg) {
                        send(nextMsg.mid, nextMsg.msg, nextMsg.flags);
                    }
                }
            } else {
                String sendMsg = process(msg, flags.userFlags());
                if (!flags.contains(Flags.RESULT_NO_NEED)) {
                    assert null != sendMsg;
                    Flags f = new Flags(Flags.RESULT);
                    send(new ID.MID("-1"), sendMsg, f);
                }
            }
        }
    }

    public final void specifyActionCallback(ID.MID mid, ActionCallback actionCallback) {
        specificActionCallbacks.put(mid, actionCallback);
    }

    private String recv() throws IOException {
        String msg;
        String separator = (String) Values.getInstance().get("separator_first_grade");
        if (!lastMessage.isEmpty()) {
            if (lastMessage.contains(separator)) {
                int index = lastMessage.indexOf(separator);
                msg = lastMessage.substring(0, index);
                lastMessage = lastMessage.substring(index + separator.length());
            } else {
                msg = lastMessage;
                lastMessage = "";
            }
        } else {
            msg = Basics.NetClass.recv(pConnection.getSocket());
            if (msg.contains(separator)) {
                int index = msg.indexOf(separator);
                lastMessage = msg.substring(index + separator.length());
                msg = msg.substring(0, index);
            }
        }
        if (msg.isEmpty()) {
            pConnection.abort();
        }
        try {
            if (rsaOn) {
                String[] msgInParas = Basics.TextClass.split(msg, separatorSecondGrade);
                if (!ARSA.verify(msgInParas[1], msgInParas[0], remotePublicKey)) {
                    throw new Basics.AccidentEvents.InvalidSignatureError("invalid signature: " + msgInParas[0] + ", pid: " + pid.toString());
                }
                msg = ARSA.decrypt(msgInParas[1], privateKey);
            }
        } catch (SignatureException | InvalidKeyException | BadPaddingException | NoSuchAlgorithmException | NoSuchPaddingException | IOException | IllegalBlockSizeException e) {
            if (errorHandlingOn) {
                return null;
            } else {
                throw new Basics.AccidentEvents.AccidentEvent(e.toString());
            }
        }
        return msg;
    }

    void send(ID.MID mid, String msg, Flags flags) {
        currentMessageIsSpecific = specificActionCallbacks.containsKey(mid);
        if (currentMessageIsSpecific) {
            specificActionCallbacks.get(mid).onSend();
        } else {
            onSend(mid);
        }

        try {
            msg = flags.toString() + separatorSecondGrade + Base64.getEncoder().encodeToString(msg.getBytes()) + separatorSecondGrade;
            if (rsaOn) {
                msg = ARSA.encrypt(msg, remotePublicKey);
                msg = ARSA.sign(msg, privateKey) + separatorSecondGrade + msg;
            }
            Basics.NetClass.send(pConnection.getSocket(), msg);

            if (currentMessageIsSpecific) {
                specificActionCallbacks.get(mid).onSent();
            } else {
                onSent(mid);
            }
        } catch (SignatureException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException | IOException e) {
            if (errorHandlingOn) {
                Log.getInstance().publish(e);
            } else {
                throw new Basics.AccidentEvents.AccidentEvent(e.toString());
            }
        }
    }

    public final ID.MID send(String msg, Flag... flags) {
        ID.MID mid = messageQueue.add(msg, new Flags(flags));

        if (messageQueue.length() == 1 || messageBlockingOn) {
            send(mid, msg, messageQueue.current().flags);
        }

        return mid;
    }

    public final ID.MID send(String msg, Flags flags) {
        ID.MID mid = messageQueue.add(msg, flags);

        if (messageQueue.length() == 1 || !messageBlockingOn) {
            send(mid, msg, messageQueue.current().flags);
        }

        return mid;
    }

    public final ID.MID send(String msg) {
        ID.MID mid = messageQueue.add(msg, new Flags());

        if (messageQueue.length() == 1 || !messageBlockingOn) {
            send(mid, msg, messageQueue.current().flags);
        }

        return mid;
    }

    private void run() {
        try {
            onStart();
            if (rsaOn && null == publicKey && null == privateKey) {
                ARSA.AKeyPair keys = ARSA.newKeys(keyLength);
                if (null == keys) {
                    return;
                }
                publicKey = keys.getPublicKey();
                privateKey = keys.getPrivateKey();
            }

            determineParameters();

            hasStarted = true;
            isAlive = true;
            onStarted();

            recvListener();

            onStopped();
        } catch (Basics.AccidentEvents.AccidentEvent | NoSuchAlgorithmException | InvalidKeySpecException e) {
            Log.getInstance().publish(e);
        } finally {
            pConnection.abort();
            onDiscard();
        }
    }

    final void start() {
        run();
    }

    final void stop() {
        hasStopped = true;
        onStop();
        send("", new Flags(Flags.KILL));
    }

    @Deprecated
    final void interrupt() {
        hasStopped = true;
        onStop();
        send(new ID.MID("-1"), "", new Flags(Flags.KILL));
    }
}
