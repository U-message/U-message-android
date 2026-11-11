package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * U Cloud client. There is no U Message server in this flow: the app talks to GitHub directly with the
 * user's own token (OAuth device flow), and GitHub Actions + GitHub Pages do the building and hosting.
 */
public final class UCloudGitHub {

    /** Public id of the GitHub OAuth App (device flow enabled). Not a secret. */
    public static final String CLIENT_ID = "Ov23liKdm8m3a3iKLmEt";

    private static final String API = "https://api.github.com/";
    private static final String KEY_ALIAS = "UCloudGitHubToken";
    private static final String WORKFLOW_FILE = "ucloud-deploy.yml";
    private static final int MAX_LOG_DOWNLOAD = 4 * 1024 * 1024;
    private static final int MAX_LOG_SHOWN = 32 * 1024;

    private static final DispatchQueue queue = new DispatchQueue("ucloud");

    public interface Task<T> {
        T run() throws Exception;
    }

    public interface Result<T> {
        void done(T value, String error);
    }

    public static final class ApiException extends IOException {
        public final int code;

        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static final class DeviceCode {
        public String deviceCode;
        public String userCode;
        public String verificationUri;
        public int intervalSeconds;
        public int expiresSeconds;
    }

    public static final class Repo {
        public String owner;
        public String name;
        public String branch;
        public boolean isPrivate;
        public boolean admin;
    }

    public static final class Project {
        public String owner;
        public String repo;
        public String branch;
        public String framework;

        public String url() {
            return UCloudWorkflow.siteUrl(owner, repo);
        }

        String path() {
            return "repos/" + owner + "/" + repo;
        }
    }

    public static final class Run {
        public long id;
        public String title;
        public String sha;
        public String status;
        public String conclusion;
        public String created;

        public boolean active() {
            return !"completed".equals(status);
        }

        public boolean success() {
            return "success".equals(conclusion);
        }
    }

    public static final class Job {
        public long id;
        public String name;
        public String conclusion;
        public String status;
        public List<String[]> steps = new ArrayList<>();
    }

    public static final class Detected {
        public Repo repo;
        public UCloudWorkflow.Plan plan;
    }

    private final int account;

    public UCloudGitHub(int account) {
        this.account = account;
    }

    /** Runs blocking GitHub work off the UI thread and returns to it. */
    public static <T> void async(Task<T> task, Result<T> result) {
        queue.postRunnable(() -> {
            T value = null;
            String error = null;
            try {
                value = task.run();
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            T v = value;
            String err = error;
            AndroidUtilities.runOnUIThread(() -> result.done(v, err));
        });
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("ucloud", Context.MODE_PRIVATE);
    }

    // ---------- token ----------

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }

    private void saveToken(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        prefs().edit().putString("token_" + account, value).apply();
    }

    private String token() {
        String value = prefs().getString("token_" + account, null);
        if (value == null) return null;
        try {
            String[] parts = value.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // A backup restored onto another phone cannot decrypt: the user simply connects again.
            FileLog.e(e);
            return null;
        }
    }

    public boolean isConnected() {
        return token() != null;
    }

    public String login() {
        return prefs().getString("login_" + account, "");
    }

    /** Forgets the token on this device. Sites and repositories on GitHub stay as they are. */
    public void disconnect() {
        prefs().edit().remove("token_" + account).remove("login_" + account).apply();
    }

    // ---------- device flow ----------

    public DeviceCode requestDeviceCode() throws Exception {
        JSONObject json = form("https://github.com/login/device/code",
                "client_id=" + enc(CLIENT_ID) + "&scope=" + enc("repo workflow"));
        if (json.has("error")) {
            throw new IOException(json.optString("error_description", json.optString("error")));
        }
        DeviceCode code = new DeviceCode();
        code.deviceCode = json.getString("device_code");
        code.userCode = json.getString("user_code");
        code.verificationUri = json.getString("verification_uri");
        code.intervalSeconds = json.optInt("interval", 5);
        code.expiresSeconds = json.optInt("expires_in", 900);
        return code;
    }

    /** Blocks until the user approves, denies or the code expires. Returns false if {@code cancelled} was set. */
    public boolean waitForApproval(DeviceCode code, boolean[] cancelled) throws Exception {
        long deadline = System.currentTimeMillis() + code.expiresSeconds * 1000L;
        int interval = Math.max(code.intervalSeconds, 5);
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            if (cancelled[0]) return false;
            JSONObject json;
            try {
                json = form("https://github.com/login/oauth/access_token", "client_id=" + enc(CLIENT_ID)
                        + "&device_code=" + enc(code.deviceCode) + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
            } catch (IOException networkBlip) {
                continue;
            }
            String token = json.optString("access_token");
            if (!token.isEmpty()) {
                saveToken(token);
                JSONObject user = new JSONObject(api("GET", "user", null));
                prefs().edit().putString("login_" + account, user.optString("login")).apply();
                return true;
            }
            String error = json.optString("error");
            if ("slow_down".equals(error)) {
                interval += 5;
            } else if (!"authorization_pending".equals(error)) {
                throw new IOException(json.optString("error_description", error));
            }
        }
        throw new IOException("expired_token");
    }

    // ---------- repositories ----------

    public List<Repo> repos() throws Exception {
        ArrayList<Repo> repos = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JSONArray array = new JSONArray(api("GET", "user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member&page=" + page, null));
            for (int i = 0; i < array.length(); i++) {
                repos.add(parseRepo(array.getJSONObject(i)));
            }
            if (array.length() < 100) break;
        }
        return repos;
    }

    private static Repo parseRepo(JSONObject o) throws Exception {
        Repo repo = new Repo();
        repo.owner = o.getJSONObject("owner").getString("login");
        repo.name = o.getString("name");
        repo.branch = o.optString("default_branch", "main");
        repo.isPrivate = o.optBoolean("private");
        JSONObject permissions = o.optJSONObject("permissions");
        repo.admin = permissions != null && permissions.optBoolean("admin");
        return repo;
    }

    public Detected detect(Repo repo) throws Exception {
        String path = "repos/" + repo.owner + "/" + repo.name;
        Set<String> files = new HashSet<>();
        JSONArray root = new JSONArray(api("GET", path + "/contents/?ref=" + enc(repo.branch), null));
        for (int i = 0; i < root.length(); i++) {
            files.add(root.getJSONObject(i).getString("name"));
        }
        Set<String> deps = new HashSet<>();
        boolean hasBuild = false;
        if (files.contains("package.json")) {
            JSONObject pkg = new JSONObject(fileText(path, "package.json", repo.branch));
            addKeys(deps, pkg.optJSONObject("dependencies"));
            addKeys(deps, pkg.optJSONObject("devDependencies"));
            JSONObject scripts = pkg.optJSONObject("scripts");
            hasBuild = scripts != null && scripts.has("build");
        }
        String nextConfig = null;
        if (files.contains("next.config.js")) {
            nextConfig = fileText(path, "next.config.js", repo.branch);
        } else if (files.contains("next.config.mjs")) {
            nextConfig = fileText(path, "next.config.mjs", repo.branch);
        }
        Detected detected = new Detected();
        detected.repo = repo;
        detected.plan = UCloudWorkflow.detect(files, deps, hasBuild, nextConfig);
        return detected;
    }

    private static void addKeys(Set<String> into, JSONObject o) {
        if (o == null) return;
        for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
            into.add(keys.next());
        }
    }

    private String fileText(String path, String file, String branch) throws Exception {
        JSONObject o = new JSONObject(api("GET", path + "/contents/" + file + "?ref=" + enc(branch), null));
        return new String(Base64.decode(o.getString("content"), Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    // ---------- projects ----------

    /** Turns on Pages and commits the workflow; that commit itself starts the first deployment. */
    public Project createProject(Detected detected) throws Exception {
        Repo repo = detected.repo;
        String path = "repos/" + repo.owner + "/" + repo.name;
        JSONObject pages = new JSONObject().put("build_type", "workflow");
        try {
            api("POST", path + "/pages", pages.toString());
        } catch (ApiException e) {
            if (e.code != 409) throw e; // 409: Pages already on, so only its source has to change
            api("PUT", path + "/pages", pages.toString());
        }
        String yaml = UCloudWorkflow.yaml(detected.plan, repo.owner, repo.name, repo.branch);
        JSONObject commit = new JSONObject()
                .put("message", "Add U Cloud deploy workflow")
                .put("branch", repo.branch)
                .put("content", Base64.encodeToString(yaml.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        try {
            JSONObject existing = new JSONObject(api("GET", path + "/contents/" + UCloudWorkflow.PATH + "?ref=" + enc(repo.branch), null));
            commit.put("sha", existing.getString("sha"));
        } catch (ApiException e) {
            if (e.code != 404) throw e;
        }
        api("PUT", path + "/contents/" + UCloudWorkflow.PATH, commit.toString());

        Project project = new Project();
        project.owner = repo.owner;
        project.repo = repo.name;
        project.branch = repo.branch;
        project.framework = detected.plan.framework;
        saveProject(project);
        return project;
    }

    public List<Project> projects() {
        ArrayList<Project> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString("projects_" + account, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Project p = new Project();
                p.owner = o.getString("owner");
                p.repo = o.getString("repo");
                p.branch = o.getString("branch");
                p.framework = o.optString("framework");
                list.add(p);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return list;
    }

    private void saveProject(Project project) throws Exception {
        List<Project> list = projects();
        removeFrom(list, project);
        list.add(0, project);
        writeProjects(list);
    }

    /** Only drops it from this list: the site and the workflow file stay on GitHub. */
    public void forgetProject(Project project) {
        List<Project> list = projects();
        removeFrom(list, project);
        try {
            writeProjects(list);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void removeFrom(List<Project> list, Project project) {
        for (Iterator<Project> it = list.iterator(); it.hasNext(); ) {
            Project p = it.next();
            if (p.owner.equalsIgnoreCase(project.owner) && p.repo.equalsIgnoreCase(project.repo)) {
                it.remove();
            }
        }
    }

    private void writeProjects(List<Project> list) throws Exception {
        JSONArray array = new JSONArray();
        for (Project p : list) {
            array.put(new JSONObject().put("owner", p.owner).put("repo", p.repo).put("branch", p.branch).put("framework", p.framework));
        }
        prefs().edit().putString("projects_" + account, array.toString()).apply();
    }

    // ---------- deployments ----------

    /** @param sha commit to rebuild and publish (rollback), or null for the latest. */
    public void deploy(Project project, String sha) throws Exception {
        JSONObject body = new JSONObject().put("ref", project.branch);
        if (sha != null) {
            if (!UCloudWorkflow.isValidSha(sha)) throw new IOException("Invalid commit");
            body.put("inputs", new JSONObject().put("sha", sha));
        }
        api("POST", project.path() + "/actions/workflows/" + WORKFLOW_FILE + "/dispatches", body.toString());
    }

    public List<Run> runs(Project project) throws Exception {
        JSONArray array = new JSONObject(api("GET", project.path() + "/actions/workflows/" + WORKFLOW_FILE + "/runs?per_page=20", null)).getJSONArray("workflow_runs");
        ArrayList<Run> runs = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            Run run = new Run();
            run.id = o.getLong("id");
            run.title = o.optString("display_title");
            run.sha = o.optString("head_sha");
            run.status = o.optString("status");
            run.conclusion = o.isNull("conclusion") ? null : o.optString("conclusion");
            run.created = o.optString("created_at");
            // A rollback's head_sha is the branch tip; the commit actually built is in its title.
            if (run.title.startsWith("Rollback ")) {
                String target = run.title.substring("Rollback ".length()).trim();
                if (UCloudWorkflow.isValidSha(target)) run.sha = target;
            }
            runs.add(run);
        }
        return runs;
    }

    public List<Job> jobs(Project project, long runId) throws Exception {
        JSONArray array = new JSONObject(api("GET", project.path() + "/actions/runs/" + runId + "/jobs", null)).getJSONArray("jobs");
        ArrayList<Job> jobs = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            Job job = new Job();
            job.id = o.getLong("id");
            job.name = o.optString("name");
            job.status = o.optString("status");
            job.conclusion = o.isNull("conclusion") ? null : o.optString("conclusion");
            JSONArray steps = o.optJSONArray("steps");
            for (int s = 0; steps != null && s < steps.length(); s++) {
                JSONObject step = steps.getJSONObject(s);
                job.steps.add(new String[]{step.optString("name"), step.isNull("conclusion") ? step.optString("status") : step.optString("conclusion")});
            }
            jobs.add(job);
        }
        return jobs;
    }

    /** The end of a job's log (errors are at the end), without the per-line timestamps. */
    public String jobLog(Project project, long jobId) throws Exception {
        String log = api("GET", project.path() + "/actions/jobs/" + jobId + "/logs", null);
        log = log.replaceAll("(?m)^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ", "");
        return log.length() > MAX_LOG_SHOWN ? log.substring(log.length() - MAX_LOG_SHOWN) : log;
    }

    // ---------- http ----------

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static JSONObject form(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } finally {
            c.disconnect();
        }
    }

    private String api(String method, String path, String body) throws Exception {
        String token = token();
        if (token == null) throw new ApiException(401, "Not connected");
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code >= 400) {
                String text = read(c.getErrorStream());
                String message;
                try {
                    message = new JSONObject(text).optString("message", "HTTP " + code);
                } catch (Exception e) {
                    message = "HTTP " + code;
                }
                if (code == 401) {
                    disconnect(); // revoked on GitHub: don't keep a dead token
                }
                throw new ApiException(code, message);
            }
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_LOG_DOWNLOAD) break;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
