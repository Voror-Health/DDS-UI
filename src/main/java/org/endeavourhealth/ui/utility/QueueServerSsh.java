package org.endeavourhealth.ui.utility;

import com.fasterxml.jackson.databind.JsonNode;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.endeavourhealth.common.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * SSH access to the queue reader servers for the Queue Server Admin page (added in the dds-ui Vue port).
 *
 * <p>The dds-ui port connected with no key (JSch does not load ~/.ssh on its own, so it could not log in),
 * cut the last character off the user name, turned host key checking off and built shell commands from
 * request values. Here:</p>
 * <ul>
 *     <li>the key and known_hosts come from the {@code queue-server-ssh} config row (defaults
 *     {@code ~/.ssh/id_rsa} and {@code ~/.ssh/known_hosts} of the Tomcat user); host keys are checked unless
 *     the row sets {@code "strict-host-key-checking": "no"}</li>
 *     <li>every value that goes into a command is checked against a strict pattern first
 *     ({@link #checkQueueName} etc.), so nothing from a request can change the command</li>
 *     <li>files are uploaded over SFTP rather than echoed through the shell</li>
 * </ul>
 */
public final class QueueServerSsh {
    private static final Logger LOG = LoggerFactory.getLogger(QueueServerSsh.class);

    public static final String CONFIG_ID = "queue-server-ssh";

    private static final Pattern QUEUE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern MEMORY = Pattern.compile("[0-9]{1,6}[mMgG]");
    private static final Pattern JAR_FILE = Pattern.compile("[A-Za-z0-9_.-]{1,100}\\.jar");
    private static final Pattern AWS_INSTANCE_ID = Pattern.compile("i-[0-9a-f]{8,17}");
    private static final Pattern USER = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:-]{1,253}");

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final long COMMAND_TIMEOUT_MS = 120_000;

    private QueueServerSsh() {
    }

    public static String checkQueueName(String s) {
        return check(s, QUEUE_NAME, "queue name");
    }

    public static String checkMemory(String s) {
        return check(s, MEMORY, "memory setting (e.g. 2g or 650m)");
    }

    public static String checkJarFileName(String s) {
        return check(s, JAR_FILE, "JAR file name");
    }

    public static String checkAwsInstanceId(String s) {
        return check(s, AWS_INSTANCE_ID, "AWS instance id");
    }

    public static String checkHost(String s) {
        return check(s, HOST, "host / IP address");
    }

    private static String check(String s, Pattern p, String what) {
        if (s == null || !p.matcher(s).matches()) {
            throw new IllegalArgumentException("Invalid " + what + ": " + (s == null ? "null" : "'" + s + "'"));
        }
        return s;
    }

    /** "user@host" from the server_ips config. */
    public record Target(String user, String host) {
        public static Target parse(String userAndIp) {
            if (userAndIp == null) {
                throw new IllegalArgumentException("No user@host for server");
            }
            int ix = userAndIp.indexOf('@');
            if (ix <= 0) {
                throw new IllegalArgumentException("Invalid user@host: " + userAndIp);
            }
            return new Target(check(userAndIp.substring(0, ix), USER, "SSH user"),
                    check(userAndIp.substring(ix + 1), HOST, "SSH host"));
        }
    }

    /** Runs a command and returns its standard output. Throws if it exits non-zero. */
    public static String exec(Target target, String command) throws Exception {
        Session session = openSession(target);
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setOutputStream(out);
            channel.setErrStream(err);
            channel.connect(CONNECT_TIMEOUT_MS);

            long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;
            while (!channel.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    channel.disconnect();
                    throw new Exception("Timed out running command on " + target.host());
                }
                Thread.sleep(200);
            }
            int exit = channel.getExitStatus();
            channel.disconnect();
            if (exit != 0) {
                throw new Exception("Command on " + target.host() + " exited with " + exit + ": "
                        + abbreviate(err.toString(StandardCharsets.UTF_8)));
            }
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            session.disconnect();
        }
    }

    /** Uploads bytes to a remote path over SFTP. */
    public static void upload(Target target, byte[] data, String remotePath) throws Exception {
        Session session = openSession(target);
        try {
            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(CONNECT_TIMEOUT_MS);
            try {
                sftp.put(new ByteArrayInputStream(data), remotePath, ChannelSftp.OVERWRITE);
                sftp.chmod(0644, remotePath);
            } finally {
                sftp.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    private static Session openSession(Target target) throws Exception {
        JsonNode cfg = null;
        try {
            cfg = ConfigManager.getConfigurationAsJson(CONFIG_ID);
        } catch (Exception ex) {
            LOG.debug("No {} config", CONFIG_ID, ex);
        }
        String home = System.getProperty("user.home");
        String keyFile = text(cfg, "private-key-file", home + File.separator + ".ssh" + File.separator + "id_rsa");
        String knownHosts = text(cfg, "known-hosts-file", home + File.separator + ".ssh" + File.separator + "known_hosts");
        String strict = text(cfg, "strict-host-key-checking", "yes");

        JSch jsch = new JSch();
        if (!new File(keyFile).isFile()) {
            throw new Exception("SSH private key " + keyFile + " not found - set 'private-key-file' in the '"
                    + CONFIG_ID + "' config");
        }
        jsch.addIdentity(keyFile);
        if (new File(knownHosts).isFile()) {
            jsch.setKnownHosts(knownHosts);
        }

        Session session = jsch.getSession(target.user(), target.host(), 22);
        session.setConfig("StrictHostKeyChecking", "no".equalsIgnoreCase(strict) ? "no" : "yes");
        session.connect(CONNECT_TIMEOUT_MS);
        return session;
    }

    private static String text(JsonNode node, String field, String dflt) {
        if (node == null) {
            return dflt;
        }
        String s = node.path(field).asText("");
        return s.isBlank() ? dflt : s;
    }

    private static String abbreviate(String s) {
        s = s == null ? "" : s.trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
