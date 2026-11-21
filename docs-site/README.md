# ETroute docs site

Docusaurus and its Mermaid theme are pinned together at 3.10.2. CI uses Node 24. Use the committed lockfile:

```bash
cd docs-site
npm ci
npm test
npm run lint
npm run build
npm start
```

The development server listens on all interfaces for Codespaces port forwarding. Keep the forwarded port private. The production build uses `/ETroute/` as its base URL and serves docs at the site root. The docs workflow builds a preview artifact; it does not deploy a public site.

The simulator does not inspect files, make network calls, launch Python, or persist admission evidence. Its inputs are assumptions.

Existing README.txt and docs/ remain untouched. Freeze documentation versions at validated release milestones. No 1.0 release snapshot is created here. CI checks Markdown/MDX style, model behavior, and production compilation. Broken local links fail the build; external evidence URLs are not fetched by the build.
