# VocaPhone website

The public VocaPhone website is a dependency-free static site.

```sh
cd web
npm run check
npm run dev
```

Then open `http://127.0.0.1:4173/`. The iPhone setup guide is available at
`http://127.0.0.1:4173/iphone/`. The consumer privacy page is at
`http://127.0.0.1:4173/privacy/`.

The site uses only local brand assets and system fonts. The Android install and
download CTAs point at the current public beta tag
(`v0.1.0-beta.14`), where the release includes the APK and its verification
files.

## Deployment

GitHub Actions deploys `web/` to Pages on every push to `main` that touches
this directory (see `.github/workflows/pages.yml`). Set Pages source to
**GitHub Actions**, then add the custom domain in repo Settings → Pages.

- Site directory: `web`
- Check command: `npm run check`
- Publish directory: `web`
- Canonical URL expected by metadata: `https://vocaphone.vocahq.com/`

`/`, `/iphone/`, `/iphone/device-setup/`, and `/privacy/` must resolve as HTML
routes.

## Pull request previews

A pull request touching `web/` deploys the site to a Cloudflare Pages preview
and comments the URL, updating that comment on every push
(`.github/workflows/web-preview.yml`).

Cloudflare rather than GitHub Pages because Pages serves one site per
repository, and this repository's is production on the custom domain. A
preview deployed there would replace the live site instead of sitting beside
it.

One-time setup:

1. Create a Cloudflare Pages project. Any name works; the workflow defaults to
   `vocaphone-web` and reads the repository variable `CLOUDFLARE_PAGES_PROJECT`
   if you pick another. Choose direct upload, not a git connection: the
   workflow uploads the directory itself, so a second connection would deploy
   the same commits twice.
2. Add repository secrets `CLOUDFLARE_API_TOKEN` (a token with the
   **Cloudflare Pages: Edit** permission) and `CLOUDFLARE_ACCOUNT_ID`.

Until both secrets exist the workflow still runs `npm run check` and then says
in the job summary that it skipped the deploy, so an unconfigured repository
does not fail its pull requests. Pull requests from forks skip the deploy for
the same reason they cannot upload: a fork's token is given no secrets.

Previews are deployed under the branch name `pr-<number>`, which keeps them off
the Pages project's production alias. They are not a merge gate — `npm run
check` in `quality-web.yml` is.

The Android product images are the original 576×1280 screenshots from
[VocaPhone PR #70](https://github.com/VocaHQ/vocaphone/pull/70). The website
frames them with CSS and does not load media from GitHub at runtime.
