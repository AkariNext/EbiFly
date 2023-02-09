# EbiFly

Economy Billing Fly Plugin for Bukkit  
[日本語解説](/README_ja.md)

(Ebi(エビ/shrimp) + Fly(フライ/fry) = EbiFly(エビフライ/[Japanese fried shrimp](https://www.google.com/search?q=japanese%20fried%20shrimp&tbm=isch)))

## Feature

- Economy required for flying. (and, economy disable mode available)
- Particle and Sound.
- ActionBar/Title message.
- Safety feature against fall, void, lava.
- Restrict feature for levitation and in-water.
- Multiple locale support.

## Installation

If you need economy feature, Please install [Vault](https://www.spigotmc.org/resources/vault.34315/) and Economy plugin.  
Looking for an easy-to-use Economy plugin? try [Jecon](https://github.com/HimaJyun/Jecon), that plugin developed by me!

(If you don't use economy feature, not need Vault and Economy plugin. In that case, this plugin work as a timed fly plugin!)

Simple 3-step installation.

1. Download this plugin from [release](https://github.com/HimaJyun/EbiFly/releases/latest).
2. Move the downloaded jar to the `plugins` directory.
3. Server restart. Editing config and Run `/fly reload` command.

# Command/Permission

## Command

|Command|Description|Permission|Default|
|:------|:----------|:----------|:-----|
|/fly|(in Flight) Disable fly.<br>(not Flying) Enable persistant fly.|fly.fly.self|ALL|
|/fly [minute] [player]|Fly for the specified time.<br>(If player is specified) Flight the player.|fly.fly.self<br>fly.fly.other|ALL|
|/fly version|Checking plugin version.|fly.version|OP|
|/fly reload|Reloading plugin config.|fly.reload|OP|
|/fly help|Show command help.|N/A|ALL|

## Restrict permission

Enabled when allowed (set to `true`), Disabled when denied (set to` false`).

|Permission|Description|Default|
|:---------|:----------|:------|
|fly.restrict.respawn|(Enable) Continue flying after respawn.<br>(Disable) Stop flying after respawn.|Enable|
|fly.restrict.world|(Enable) Continue flying after change the world.<br>(Disable) Stop flying after change the world.|Enable|
|fly.restrict.gamemode|(Enable) Continue flying after switching game modes.<br>(Disable) Stop flying after switching game modes.|Disable|
|fly.restrict.levitation|(Enable) Continue flying after receiving a levitation debuff.<br>(Disable) Stop flying after receiving a levitation debuff.|OP only Enable|
|fly.restrict.water|(Enable) Continue flying after entering the water.<br>(Disable) Stop flying after entering the water.|OP only Enable|

This feature can also be setting in `config.yml`.

## Other permission

|Permission|Description|Default|
|:---------|:----------|:------|
|fly.free|Free for flight.|OP|
|fly.fly.*|Include following permissions:<br>fly.fly.self<br>fly.fly.other|N/A|
|fly.restrict.*|Include following permissions:<br>ifly.restrict.respawn<br>fly.restrict.world<br>fly.restrict.gamemode<br>fly.restrict.levitation<br>fly.restrict.water|N/A|
|fly.op|Include following permissions:<br>fly.free<br>fly.version<br>fly.reload<br>fly.restrict.water<br>fly.restrict.levitation|N/A|
|fly.user|Include following permissions:<br>fly.fly.self<br>fly.fly.other<br>fly.restrict.respawn<br>fly.restrict.world<br>|N/A|
|fly.*|Include all permissions.|N/A|

# Configuration

Please refer comments in yaml file.

## restrict

This setting enable/disable the permission and event check.  
Therefore, the behavior when set to `false` differs depending on the setting item, and the behavior is not the same as the permission.

This setting is for performance, for expert.

## economy

If you want to use the economy feature, change `economy.enable` to` true` in `config.yml`.

```yml
economy:
  enable: false # <- Change it from false to true
```

If economy enabled, required Vault and Economy plugin.  
If these are not installed when `true`, it will not start with an error.

## locale

This plugin support multiple locale system. This system refer player settings.

Plugin include English(`en_us.yml`) and Japanese(`ja_jp.yml`). (Note: Developer is native Japanese speaker, not native English speaker. translation may contain errors.)

If you want add other locale? Please craete `locale/<your locale>.yml` file. (Recommend copy from `en_us.yml`)

How to check `<your locale>`? Run `/fly version` command, Your locale showing to `Locale` line.

```yml
Locale: <current locale> (<your locale>)
```

Using `<your locale>` for your locale file name. (eg: Korean -> `ko_kr.yml`)
