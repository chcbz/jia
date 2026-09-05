package cn.jia.agent.service.funding;

import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Length-prefixed semantic request digest; it never logs or persists request-body bytes. */
public final class FundedBountyRequestDigest {
    private FundedBountyRequestDigest() {
    }

    public static byte[] create(AgentTaskCreateDTO request) {
        if (request == null) return digest(fields("funded-bounty-create-v0", null));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            write(out, "funded-bounty-create-v0");
            write(out, request.getTitle());
            write(out, request.getDescription());
            writeStrings(out, request.getRequiredAbilities());
            write(out, request.getReward() == null ? null : Integer.toString(request.getReward()));
            write(out, request.getGrossBountyAmountMicro());
            write(out, request.getSettlementPolicy());
            List<AgentSkillRequirementDTO> requirements = request.getRequiredSkillRequirements();
            if (requirements == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(requirements.size());
                for (AgentSkillRequirementDTO requirement : requirements) {
                    out.writeBoolean(requirement != null);
                    if (requirement != null) {
                        write(out, requirement.getSkillKey());
                        write(out, requirement.getVersionRange());
                    }
                }
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to hash funded bounty request", impossible);
        }
        return digest(bytes.toByteArray());
    }

    public static byte[] cancel(String taskId, String expectedTaskVersion) {
        return digest(fields("funded-bounty-cancel-v0", taskId, expectedTaskVersion));
    }

    private static byte[] fields(String... values) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (String value : values) write(out, value);
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to hash funded bounty request", impossible);
        }
        return bytes.toByteArray();
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        if (values == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(values.size());
        for (String value : values) write(out, value);
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
