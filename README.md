> **Downstream fork** of [Stirling-PDF](https://github.com/Stirling-Tools/Stirling-PDF), maintained by [@nonofr91](https://github.com/nonofr91) for print-production workflows.
>
> The `prepress` branch adds on top of upstream:
>
> **Print-prepress tools**
> - **Cut Contour** — silhouette extraction (alpha / background / AI matting) into a `CutContour` spot color with overprint, optional content layer and bleed, ready for RIPs and plotters
> - **Page boxes** — set/derive TrimBox, BleedBox & co. (from painted crop marks too), box-aware crop and scale, outlines drawn on a viewer layer
> - **Generative bleed & crop marks**, **text-to-outlines**
>
> **Print preflight**
> - Checks for fonts, RGB/spot colors, bleed, hairlines, transparency and ink coverage — including per-pixel rendered TAC via Ghostscript
> - Technical separations and non-printing layers (cut, crease, foil, white…) kept out of print findings
> - Fixups — RGB→CMYK, spot→CMYK (pixel-wise on images), clip-to-CropBox, ink reduction — with a dry-run preview and a per-fixup audit trail
> - Findings located on the page in an annotated copy scaled to A4; named profiles; reports localized (en/fr); every run emits a pass/warn/fail verdict
>
> **Versioned archive** — every prepress operation is kept server-side, chained by content hash: the source is version 1, each transform a new version, audits recorded without a bump
>
> **Automation pipelines**
> - Sources: FTP, SFTP, SMB, watched folders, S3, editor upload — destinations: folder, S3, branded e-mail report, or back to the editor
> - Steps run per file or fan-in, may carry a report, and can be gated per document (`when`) on document facts or an earlier step's report — e.g. run a fixup only where the verdict failed, or only where a given check fired
> - Delivery routing on the same facts, plus an AI classification step for document-type routing
>
> **Roadmap** — deeper preflight and correction, toward a full prepress automation stack:
> - More checks: overprinting white/objects, effective image resolution, mixed page geometry, spot-color aliases, annotations and layers in the print area
> - More fixups: ICC conversions, GCR/UCR black generation and TAC reduction, image resampling to target DPI, transparency flattening, bleed extension to BleedBox
> - PDF/X export (X-1a / X-4 output intents), separations and ink preview in the viewer
> - Imposition — booklets, step-and-repeat, ganging
> - Shareable preflight profiles, machine-readable reports for MIS integration
> - Bypass-aware chain validation for gated pipeline steps
>
> Images: [`pubgen/stirling-pdf`](https://hub.docker.com/r/pubgen/stirling-pdf) (`prepress-<sha>` tags). Upstream sync: [`upstream-sync.yml`](.github/workflows/upstream-sync.yml) — auto-deploy: [`prepress-deploy.yml`](.github/workflows/prepress-deploy.yml).
>
> Everything below is the upstream README.

<p align="center">
  <img src="https://raw.githubusercontent.com/Stirling-Tools/Stirling-PDF/main/docs/stirling.png" width="80" alt="Stirling PDF logo">
</p>

<h1 align="center">Stirling PDF - The Open-Source PDF Platform</h1>

Stirling PDF is a powerful, open-source PDF editing platform. Run it as a personal desktop app, in the browser, or deploy it on your own servers with a private API. Edit, sign, redact, convert, and automate PDFs without sending documents to external services.

<p align="center">
  <a href="https://hub.docker.com/r/stirlingtools/stirling-pdf">
    <img src="https://img.shields.io/docker/pulls/frooodle/s-pdf" alt="Docker Pulls">
  </a>
  <a href="https://discord.gg/HYmhKj45pU">
    <img src="https://img.shields.io/discord/1068636748814483718?label=Discord" alt="Discord">
  </a>
  <a href="https://scorecard.dev/viewer/?uri=github.com/Stirling-Tools/Stirling-PDF">
    <img src="https://api.scorecard.dev/projects/github.com/Stirling-Tools/Stirling-PDF/badge" alt="OpenSSF Scorecard">
  </a>
  <a href="https://github.com/Stirling-Tools/stirling-pdf">
    <img src="https://img.shields.io/github/stars/stirling-tools/stirling-pdf?style=social" alt="GitHub Repo stars">
  </a>
</p>

![Stirling PDF - Dashboard](images/home-light.png)

## Key Capabilities

- **Everywhere you work** - Desktop client, browser UI, and self-hosted server with a private API.
- **50+ PDF tools** - Edit, merge, split, sign, redact, convert, OCR, compress, and more.
- **Automation & workflows** - No-code pipelines direct in UI with APIs to process millions of PDFs.
- **Enterprise‑grade** - SSO, auditing, and flexible on‑prem deployments.
- **Developer platform** - REST APIs available for nearly all tools to integrate into your existing systems.
- **Global UI** - Interface available in 40+ languages.

For a full feature list, see the docs: **https://docs.stirlingpdf.com**

## Quick Start

```bash
docker run -p 8080:8080 docker.stirlingpdf.com/stirlingtools/stirling-pdf
```

Then open: http://localhost:8080

For full installation options (including desktop and Kubernetes), see our [Documentation Guide](https://docs.stirlingpdf.com/#documentation-guide).

## Resources

- [**Documentation**](https://docs.stirlingpdf.com)
- [**Homepage**](https://stirling.com)
- [**API Docs**](https://registry.scalar.com/@stirlingpdf/apis/stirling-pdf-processing-api/)
- [**Server Plan & Enterprise**](https://docs.stirlingpdf.com/Paid-Offerings)

## Support

- **Community**: [Discord](https://discord.gg/HYmhKj45pU)
- **Bug Reports**: [GitHub Issues](https://github.com/Stirling-Tools/Stirling-PDF/issues)

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

This project uses [Task](https://taskfile.dev/) as a unified command runner for all build, dev, and test commands. Run `task dev` to get started running the editor, run `task` to see the most common commands, or see the [Developer Guide](DeveloperGuide.md) for full details.

For adding translations, see the [Translation Guide](devGuide/HowToAddNewLanguage.md).

## License

Stirling PDF is open-core. See [LICENSE](LICENSE) for details.
