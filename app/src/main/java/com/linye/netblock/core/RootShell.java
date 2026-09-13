package com.linye.netblock.core;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Root 模式：通过 su 执行 iptables 规则实现 100% 丢包。
 * 使用自定义链 NB_OUT / NB_IN（含 localhost 豁免），幂等可反复清理。
 */
public final class RootShell {

    private static final String CHAIN_OUT = "NB_OUT";
    private static final String CHAIN_IN = "NB_IN";

    private RootShell() {
    }

    /** 检测设备是否有可用的 root（su + uid=0）。 */
    public static boolean isAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            r.close();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** 开启(true)/关闭(false) 100% 丢包。 */
    public static boolean apply(boolean block) {
        List<String> cmds = new ArrayList<>();
        cmds.add("iptables -N " + CHAIN_OUT);
        cmds.add("iptables -N " + CHAIN_IN);
        cmds.add("iptables -F " + CHAIN_OUT);
        cmds.add("iptables -F " + CHAIN_IN);
        if (block) {
            cmds.add("iptables -A " + CHAIN_OUT + " -o lo -j ACCEPT");
            cmds.add("iptables -A " + CHAIN_OUT + " -j DROP");
            cmds.add("iptables -A " + CHAIN_IN + " -i lo -j ACCEPT");
            cmds.add("iptables -A " + CHAIN_IN + " -j DROP");
            cmds.add("iptables -I OUTPUT 1 -j " + CHAIN_OUT);
            cmds.add("iptables -I INPUT 1 -j " + CHAIN_IN);
            // IPv6 同步处理（失败不影响结果）
            cmds.add("ip6tables -N " + CHAIN_OUT + " 2>/dev/null");
            cmds.add("ip6tables -F " + CHAIN_OUT + " 2>/dev/null");
            cmds.add("ip6tables -A " + CHAIN_OUT + " -o lo -j ACCEPT 2>/dev/null");
            cmds.add("ip6tables -A " + CHAIN_OUT + " -j DROP 2>/dev/null");
            cmds.add("ip6tables -I OUTPUT 1 -j " + CHAIN_OUT + " 2>/dev/null");
        } else {
            cmds.add("iptables -D OUTPUT -j " + CHAIN_OUT + " 2>/dev/null");
            cmds.add("iptables -D INPUT -j " + CHAIN_IN + " 2>/dev/null");
            cmds.add("iptables -F " + CHAIN_OUT);
            cmds.add("iptables -F " + CHAIN_IN);
            cmds.add("iptables -X " + CHAIN_OUT);
            cmds.add("iptables -X " + CHAIN_IN);
            cmds.add("ip6tables -D OUTPUT -j " + CHAIN_OUT + " 2>/dev/null");
            cmds.add("ip6tables -F " + CHAIN_OUT + " 2>/dev/null");
            cmds.add("ip6tables -X " + CHAIN_OUT + " 2>/dev/null");
        }
        return exec(cmds);
    }

    /** App 冷启动安全清理：无论上次状态如何，把自定义链从内核里摘干净。 */
    public static void cleanupQuietly() {
        List<String> cmds = new ArrayList<>();
        cmds.add("iptables -D OUTPUT -j " + CHAIN_OUT + " 2>/dev/null");
        cmds.add("iptables -D INPUT -j " + CHAIN_IN + " 2>/dev/null");
        cmds.add("iptables -F " + CHAIN_OUT + " 2>/dev/null");
        cmds.add("iptables -F " + CHAIN_IN + " 2>/dev/null");
        cmds.add("iptables -X " + CHAIN_OUT + " 2>/dev/null");
        cmds.add("iptables -X " + CHAIN_IN + " 2>/dev/null");
        cmds.add("ip6tables -D OUTPUT -j " + CHAIN_OUT + " 2>/dev/null");
        cmds.add("ip6tables -F " + CHAIN_OUT + " 2>/dev/null");
        cmds.add("ip6tables -X " + CHAIN_OUT + " 2>/dev/null");
        try {
            exec(cmds);
        } catch (Exception ignore) {
        }
    }

    private static boolean exec(List<String> cmds) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream out = new DataOutputStream(p.getOutputStream());
            for (String c : cmds) {
                out.writeBytes(c + "\n");
                out.flush();
            }
            out.writeBytes("exit\n");
            out.flush();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
