package org.telegram.messenger;

import java.util.Locale;
import java.util.Set;

/** U Cloud: framework detection and the GitHub Actions workflow that builds and publishes to GitHub Pages. No Android deps. */
public final class UCloudWorkflow {

    public static final String PATH = ".github/workflows/ucloud-deploy.yml";

    public static final int OK = 0;
    public static final int ERR_UNSUPPORTED = 1;
    public static final int ERR_NEXT_SSR = 2;
    public static final int ERR_NO_BUILD = 3;

    public static final class Plan {
        public int error = OK;
        public String framework;
        public String output;
        /** vite | cra | next | static */
        public String kind;
        /** npm | yarn | pnpm; null for static */
        public String manager;
    }

    private UCloudWorkflow() {
    }

    /** @param nextConfig contents of next.config.js/.mjs; null if the repo has neither. */
    public static Plan detect(Set<String> rootFiles, Set<String> deps, boolean hasBuildScript, String nextConfig) {
        Plan plan = new Plan();
        if (!rootFiles.contains("package.json")) {
            if (rootFiles.contains("index.html")) {
                plan.kind = "static";
                plan.framework = "Static HTML";
                plan.output = ".";
            } else {
                plan.error = ERR_UNSUPPORTED;
            }
            return plan;
        }
        plan.manager = rootFiles.contains("pnpm-lock.yaml") ? "pnpm" : rootFiles.contains("yarn.lock") ? "yarn" : "npm";
        if (deps.contains("next")) {
            plan.kind = "next";
            plan.framework = "Next.js (static export)";
            plan.output = "out";
            if (nextConfig == null || !nextConfig.matches("(?s).*output\\s*:\\s*['\"]export['\"].*")) {
                plan.error = ERR_NEXT_SSR;
            }
        } else if (deps.contains("vite")) {
            plan.kind = "vite";
            plan.framework = deps.contains("vue") ? "Vue (Vite)" : deps.contains("react") ? "React (Vite)" : deps.contains("svelte") ? "Svelte (Vite)" : "Vite";
            plan.output = "dist";
        } else if (deps.contains("react-scripts")) {
            plan.kind = "cra";
            plan.framework = "Create React App";
            plan.output = "build";
        } else {
            plan.error = ERR_UNSUPPORTED;
            return plan;
        }
        if (plan.error == OK && !hasBuildScript) {
            plan.error = ERR_NO_BUILD;
        }
        return plan;
    }

    /** Project pages are served from /repo/; a user site (owner.github.io) from the root. */
    public static String basePath(String owner, String repo) {
        return repo.toLowerCase(Locale.ROOT).equals(owner.toLowerCase(Locale.ROOT) + ".github.io") ? "/" : "/" + repo + "/";
    }

    public static String siteUrl(String owner, String repo) {
        return "https://" + owner.toLowerCase(Locale.ROOT) + ".github.io" + basePath(owner, repo);
    }

    public static boolean isValidSha(String sha) {
        return sha != null && sha.matches("[0-9a-fA-F]{7,40}");
    }

    public static String yaml(Plan plan, String owner, String repo, String branch) {
        String base = basePath(owner, repo);
        StringBuilder y = new StringBuilder();
        y.append("# Managed by U Cloud (U Message). Builds this repository and publishes it to GitHub Pages.\n");
        y.append("name: U Cloud Deploy\n");
        y.append("run-name: ${{ inputs.sha && format('Rollback {0}', inputs.sha) || github.event.head_commit.message || 'Deploy' }}\n");
        y.append("on:\n  push:\n    branches: ['").append(branch).append("']\n");
        y.append("  workflow_dispatch:\n    inputs:\n      sha:\n        description: Commit to deploy (empty = latest)\n        required: false\n        default: ''\n");
        y.append("permissions:\n  contents: read\n  pages: write\n  id-token: write\n");
        y.append("concurrency:\n  group: ucloud-pages\n  cancel-in-progress: false\n");
        y.append("jobs:\n  build:\n    runs-on: ubuntu-latest\n    timeout-minutes: 15\n    steps:\n");
        y.append("      - uses: actions/checkout@v7\n        with:\n          ref: ${{ inputs.sha || github.sha }}\n");
        boolean node = !"static".equals(plan.kind);
        if (node) {
            y.append("      - uses: actions/setup-node@v7\n        with:\n          node-version: lts/*\n");
        }
        if ("next".equals(plan.kind)) {
            y.append("      - uses: actions/configure-pages@v6\n        with:\n          static_site_generator: next\n");
        } else {
            y.append("      - uses: actions/configure-pages@v6\n");
        }
        if (node) {
            if (!"npm".equals(plan.manager)) {
                y.append("      - run: corepack enable\n");
            }
            y.append("      - name: Install\n        run: ").append(installCommand(plan.manager)).append('\n');
            y.append("      - name: Build\n");
            if ("cra".equals(plan.kind)) {
                y.append("        env:\n          PUBLIC_URL: ").append(base).append('\n');
            }
            y.append("        run: ").append(buildCommand(plan, base)).append('\n');
        }
        y.append("      - uses: actions/upload-pages-artifact@v5\n        with:\n          path: ").append(plan.output).append('\n');
        y.append("  deploy:\n    needs: build\n    runs-on: ubuntu-latest\n    timeout-minutes: 10\n");
        y.append("    environment:\n      name: github-pages\n      url: ${{ steps.deployment.outputs.page_url }}\n");
        y.append("    steps:\n      - id: deployment\n        uses: actions/deploy-pages@v5\n");
        return y.toString();
    }

    private static String installCommand(String manager) {
        if ("pnpm".equals(manager)) return "pnpm install --frozen-lockfile";
        if ("yarn".equals(manager)) return "yarn install";
        return "if [ -f package-lock.json ]; then npm ci; else npm install; fi";
    }

    private static String buildCommand(Plan plan, String base) {
        String baseArg = "vite".equals(plan.kind) ? " --base=" + base : "";
        if ("pnpm".equals(plan.manager)) return "pnpm run build" + (baseArg.isEmpty() ? "" : " " + baseArg.trim());
        if ("yarn".equals(plan.manager)) return "yarn build" + baseArg;
        return "npm run build" + (baseArg.isEmpty() ? "" : " --" + baseArg);
    }
}
