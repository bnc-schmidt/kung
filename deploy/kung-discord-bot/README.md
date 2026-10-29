# Kung forum bot

Gives every new post in the Suggestions and Bug Reports forums a fixed tag (`Under Review`,
`Investigating`), whatever the creator picked. One file, no dependencies, Node 22 or newer.

Discord cannot do this alone: a forum has no default tag. What Discord can do is mark a tag
"moderators only", so only members with **Manage Threads** can add or remove it. The bot has that
permission and sets the tag; the creator cannot remove it. The bot only listens for new posts
(`THREAD_CREATE`) and needs only the Guilds intent, no privileged intent.

## 1. Create the bot (Discord Developer Portal)

1. <https://discord.com/developers/applications> → **New Application** → name it, e.g. `Kung Forum Bot`.
2. **Bot** tab → **Reset Token** → copy it. It goes only into the env file in step 4, never into
   the repo, chat or a screenshot. Leave all three **Privileged Gateway Intents** off.
   Turn **Public Bot** off.
3. Copy the **Application ID** (General Information tab) and open this link, logged in as server
   owner, to add the bot to your server:

   ```text
   https://discord.com/oauth2/authorize?client_id=<APPLICATION_ID>&scope=bot&permissions=17179870208
   ```

   `17179870208` is View Channels + Manage Threads and nothing else.

## 2. Set up the forums

For each forum (Suggestions, Bug Reports):

1. Edit channel → **Tags**: create `Under Review` (Suggestions) or `Investigating` (Bug Reports).
   Tag names are matched without regard to case.
2. Turn on **Moderators only** for every tag in the forum, so creators cannot add, remove or
   change any tag. Leave **Require tag** off, or creators could not post.
3. Check **Permissions**: only your moderator roles (and you) have **Manage Threads** or
   **Manage Posts** in the forum. `@everyone` must not. The bot role needs View Channel and
   Manage Threads here (it has both from the invite, unless an override in the channel denies them).
4. Copy the forum's channel ID: Discord **Settings → Advanced → Developer Mode** on, then right
   click the forum → **Copy Channel ID**.

## 3. Install on the Kung LXC

The bot runs next to `kung-server` as its own service. It only connects outward, no tunnel or port.
Check `node -v` first: it must be 22 or newer (the server README installs 24).

```bash
mkdir -p /root/kung-bot && cd /root/kung-bot
# upload kung-discord-bot.mjs and .env.example here (WinSCP), then:
cp .env.example kung-bot.env
chmod 600 kung-bot.env
nano kung-bot.env          # paste the token and the two <forum id>:<tag name> rules
```

Create the service:

```bash
cat > /etc/systemd/system/kung-bot.service <<EOF
[Unit]
Description=Kung forum tag bot
After=network-online.target
Wants=network-online.target

[Service]
WorkingDirectory=/root/kung-bot
EnvironmentFile=/root/kung-bot/kung-bot.env
ExecStart=$(command -v node) /root/kung-bot/kung-discord-bot.mjs
Restart=on-failure
RestartSec=10
RestartPreventExitStatus=78
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=no
PrivateTmp=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now kung-bot
journalctl -u kung-bot -f
```

Expected log: `ready as <bot name>`, then one `rule ok: forum "..." -> "..."` per forum. A
`WARNING` there means a wrong channel ID, no access for the bot, or a tag name typo.

Exit code 78 (wrong config, bad token, intent error) is not retried, because Discord limits
sign-ins to 1000 a day. Fix the cause, then `systemctl restart kung-bot`.

## 4. Try it

Use a member **without** Manage Threads (a second account or a friend):

1. Post in Suggestions, picking any tag or none. Within a second the post shows `Under Review`.
   Same in Bug Reports with `Investigating`. The log shows `tag set to ...`.
2. As that member, try to change the tags of the post. It must not work.
3. As a moderator, change the tag. It must work and stay (the bot never touches old posts).
4. Look at the post as that member: is the moderator-only tag visible? Discord has hidden
   moderator-only tags from normal members at times. If it is hidden, the lock works but the
   creator does not see the status. Tell the maintainer: the fallback is a normal tag plus a bot
   that restores it on `THREAD_UPDATE`.

## Tests

```bash
node --test deploy/kung-discord-bot/kung-discord-bot.test.mjs
```

The tests cover the rule parsing and the tag logic with a fake Discord API. The gateway loop
(connect, heartbeat, reconnect) is only checked against the real Discord: a wrong token must
end with `gateway closed with 4004` and exit code 78.

## Limits

- No gateway RESUME: a post created during a reconnect gap (seconds) stays untagged.
- The bot replaces all tags of a new post with the one rule tag.
