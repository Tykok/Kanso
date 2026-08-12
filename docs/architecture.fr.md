# Kanso — architecture et limites du miroir Notion

## La décision dont tout découle

**Postgres est la source de vérité opérationnelle. Notion est un miroir asynchrone.**

Notion ne peut pas s'intercaler entre deux personnes qui éditent le même board : un
appel prend environ 300 à 800 ms, l'intégration est plafonnée autour de 3 requêtes
par seconde, et il n'existe aucun mécanisme de push sur lequel on puisse s'appuyer.
Tout ce qui transite par Notion hérite de ces chiffres.

Kanso écrit donc dans Postgres, répond à l'utilisateur, puis pousse vers Notion
ensuite. L'application reste pleinement utilisable quand Notion est injoignable, et
se réconcilie plus tard.

```
  navigateur ──REST──▶ API Spring ──▶ Postgres   (source de vérité)
     ▲                     │              │
     └──STOMP/WS───────────┘              │ pg_notify
                                          ▼
                              chaque instance d'API
                                          │
                                          ▼
                              sync_jobs (outbox) ──▶ Notion   (miroir)
                                          ▲             │
                                          └── poller ◀───┘
```

---

## Conséquences qu'il faut énoncer clairement

### Notion est en lecture seule dans les faits

La règle de conflit est *Kanso gagne* : si la ligne Postgres a changé après le
dernier push réussi, une modification faite dans Notion est écartée et un push
correctif est mis en file. Notion reconverge de lui-même.

Cela signifie que **quelqu'un qui édite dans Notion verra sa modification annulée**.
C'est le design, pas un bug — mais il faut le communiquer aux personnes qui
consultent le miroir, sinon elles perdent confiance dans l'outil. Mettre une
bannière sur la page parente indiquant que les bases sont maintenues par Kanso.

Si l'édition côté Notion doit un jour devenir un cas de première classe, le chemin
entrant a besoin d'un merge champ par champ et d'une vraie UI de conflit. C'est une
discussion de v2, pas un réglage.

### La synchro entrante ne couvre que les champs scalaires

Le poller applique le titre, le statut, la priorité, les dates et le flag archivé.
Il n'applique **pas** les relations ni les personnes :

- Notion remplace un tableau de relations en bloc ; accepter ce retour transformerait
  une édition concurrente en perte de données silencieuse.
- `people` ne peut pas représenter un utilisateur Kanso sans compte Notion : le
  relire supprimerait discrètement des assignés.

Les relations et les assignations restent sous l'autorité de Kanso.

### Les pages créées dans Notion ne sont pas adoptées

Un ticket a besoin d'une équipe et d'un numéro par équipe, deux choses qu'une page
créée à la main dans Notion ne peut pas fournir. Le poller journalise ces pages et
passe son chemin plutôt que d'inventer des lignes. Les tickets se créent dans Kanso.

---

## Là où le mapping Notion est lossy

| # | Problème | Ce que fait Kanso |
|---|---|---|
| 1 | Une propriété `people` n'accepte que des membres du workspace Notion : un utilisateur Kanso sans compte Notion ne peut pas y figurer. | Écrire `people` quand `users.notion_person_id` est connu, et toujours écrire en parallèle un rich-text `Assignees (Kanso)` avec le nom de tout le monde. Le miroir reste lisible ; la colonne texte est ignorée au retour. |
| 2 | Une relation exige que sa page cible existe déjà. | Les jobs portent une priorité de dépendance (équipe 10 → projet 20 → ticket 30 → doc 40) et un push dont la cible n'a pas encore de page est différé de quelques secondes au lieu d'échouer. |
| 3 | Écrire une relation remplace tout le tableau ; il n'y a pas de merge. | Chaque push écrit l'état complet de l'entité depuis Postgres. Cohérent avec « Kanso gagne », et c'est la raison pour laquelle aucun push partiel n'est tenté. |
| 4 | **Une relation ne peut pointer que vers des pages situées dans la data source qu'elle cible.** Les docs de Kanso sont des pages arbitraires ailleurs dans le workspace : les projets ne peuvent donc pas s'y relier directement. | La base miroir `Docs` contient une ligne d'index par page référencée, portant son URL, et les relations pointent vers ces lignes. `notion_docs` stocke donc deux ids : `notion_page_id` (la vraie page) et `mirror_page_id` (la ligne d'index). |
| 5 | Nos propres écritures déplacent `last_edited_time` et reviennent comme des changements. | Deux garde-fous, tous deux nécessaires : ignorer les pages dont le `last_edited_by` est notre bot, **et** ignorer tout ce qui n'est pas strictement plus récent que le `notion_last_edited_time` enregistré par le dernier push. Un humain peut éditer dans la même seconde que nous, d'où l'insuffisance d'un seul garde-fou. |
| 6 | Écrire une option `select` inconnue la crée silencieusement, donc le vocabulaire de statuts dérive. | Le vocabulaire est fermé côté Kotlin *et* par une contrainte `CHECK`. Une valeur inconnue qui revient est journalisée et ignorée, jamais adoptée. |
| 7 | Notion n'a qu'une propriété date avec `start`/`end` ; une plage relue avec un seul `start` est ambiguë (date de début ou échéance ?). | **Écart délibéré par rapport à la spec initiale :** les tickets et les projets portent deux propriétés de date distinctes (`Start`, `Due` / `End`) plutôt qu'une plage. Moins joli dans les timelines Notion, mais l'aller-retour se fait sans deviner. |
| 8 | Notion archive, il ne supprime pas. | Une suppression Kanso archive la page ; une page déplacée dans la corbeille Notion passe `archived = true` dans Kanso. Il n'y a pas de suppression définitive miroir. |
| 9 | L'API 2025-09-03 imbrique les data sources sous les databases, et la référence de schéma publiée documente toujours `database_id` pour les relations. | Les ids de database et de data source sont tous deux découverts au bootstrap et persistés dans `notion_databases` — jamais devinés. Pour les configs de relation, la forme récente `data_source_id` est essayée en premier et retombe sur `database_id` en cas d'erreur de validation, en journalisant celle que le workspace a acceptée. |
| 10 | `Kanso ID` n'est pas unique côté Notion : une page dupliquée produit deux lignes revendiquant la même entité. | La réconciliation se fait d'abord par `notion_page_id` ; `Kanso ID` n'est qu'une clé de récupération. |
| 11 | La relation d'équipe auto-référencée de Notion accepte un cycle. | L'acyclicité est imposée dans Postgres avec `WITH RECURSIVE`, à l'aller comme au retour. Le parentage d'équipe n'est jamais accepté depuis Notion. |

---

## Mécanismes

### L'outbox

Les lignes `sync_jobs` sont insérées **dans la transaction métier**, si bien qu'un
crash juste après le commit ne peut pas perdre un push.

- Un index unique partiel n'autorise au plus qu'un job `pending` par entité. Comme un
  push écrit la ligne entière, cinq pushs en file sont redondants — l'insert
  fusionne.
- La prise de job bascule la ligne en `running`, ce qui libère ce slot : une édition
  faite pendant qu'un push est en vol est quand même mise en file.
- Les prises utilisent `FOR UPDATE SKIP LOCKED`, donc plusieurs workers ou instances
  d'API prennent des ensembles disjoints au lieu de se bloquer sur la même ligne de
  tête.
- Les retries font un backoff exponentiel **avec jitter** — sans lui, une panne Notion
  fait retenter tous les jobs en file au même instant et redéclenche le rate limit.
- L'attente d'une dépendance ou du rate limiter est un *report*, pas un *retry* : elle
  ne consomme pas de tentative, parce que rien ne cloche dans le job.

### Temps réel

L'API ne pousse pas directement vers son propre broker. Elle appelle `pg_notify`
**après commit**, et chaque instance est en `LISTEN` :

- Après commit, parce qu'émettre à l'intérieur de la transaction laisse un client
  refetcher et voir des données périmées.
- Via Postgres, parce que cela diffuse vers toutes les instances sans Redis ni
  sticky sessions — et l'instance qui a fait le changement le reçoit exactement comme
  ses pairs, donc il n'y a qu'un seul chemin de broadcast à raisonner.

Les événements portent un id et assez de contexte pour décider si une vue est
concernée. Les récepteurs refetchent. Envoyer les entités entières impliquerait deux
définitions de leur forme, et `pg_notify` plafonne de toute façon les payloads à
8000 octets.

La connexion `LISTEN` est ouverte directement plutôt qu'empruntée à Hikari : une
connexion du pool garée indéfiniment réduit le pool et se fait recycler sous vos
pieds par `maxLifetime`. La perdre reste attendu, donc la boucle se reconnecte.

### Authentification

Login OAuth2 (Google, GitHub) se terminant par un cookie de session. Le même cookie
authentifie le handshake WebSocket, donc le temps réel n'a besoin d'aucune plomberie
de token.

Les tokens CSRF sont **désactivés**, délibérément et avec une justification bornée :
le cookie de session est `SameSite=Lax`, donc une page tierce ne peut pas faire
joindre ce cookie par le navigateur à un POST/PATCH/DELETE, et toute mutation est
l'un de ces trois verbes. Les lectures sont sans effet de bord.
**Si Kanso a un jour besoin d'un cookie cross-site (`SameSite=None`) ou acquiert un
GET qui modifie l'état, la protection CSRF doit être réactivée.**

Sans provider configuré, l'application retombe sur un mode dev où l'identité vient
d'un en-tête `X-Kanso-User` et où rien n'est vérifié. Elle journalise un
avertissement bien visible. Ne jamais exposer une instance tournant dans ce mode.

**L'appartenance à une équipe décide désormais qui peut déplacer un ticket, pas
seulement qui appartient à quoi.** `TicketAccess.claimedBy` est une règle en trois
volets, essayés dans l'ordre : l'appartenance directe à l'équipe du ticket ; à défaut,
**une équipe vide est une équipe ouverte** — une équipe sans aucune ligne dans
`team_members` laisse n'importe quel membre éditer ses tickets ; à défaut, l'appartenance
à une équipe **ancêtre**. Un `admin` ou le `owner` de l'instance — les deux rôles
habilités à configurer l'instance — court-circuite toute la règle.

La clause de l'équipe vide est la garantie de migration, pas une facilité : toute
instance tournant avant cette branche a un `team_members` vide, et aucune migration ne
le remplit. Sans cette clause, déployer cette fonctionnalité verrouillerait les tickets
de chaque board existant derrière un 403 dont le seul remède serait une invite SQL.

La filiation ne joue **que vers le bas** : un membre d'une équipe parente peut déplacer
le travail de n'importe laquelle de ses sous-équipes, jamais l'inverse. L'autre sens
ferait de l'adhésion à la plus petite équipe de l'instance une voie d'accès à la plus
grande — le sens de la filiation est une propriété de sécurité, pas un détail
d'implémentation.

### Projets propriété d'une équipe

Le projet d'un ticket doit appartenir à l'équipe du ticket — vérifié à l'identique dans
`create` et dans `patch` — à une exception près : un projet qui n'appartient lui-même à
aucune équipe est **transverse et appartient à tout le monde**. C'est la seule
configuration où un projet porte les tickets de plus d'une équipe : un projet qui a
choisi une équipe ne peut plus jamais apparaître sur le board d'une seconde. La règle
vit dans `TicketService`, pas dans une contrainte de base de données, parce qu'une
contrainte interdirait aussi les projets sans équipe que la barre latérale affiche déjà
dans leur propre section — le cas transverse est voulu, ce n'est pas un trou que le
schéma devrait combler.

L'élargissement de périmètre de la timeline — faire apparaître tous les autres qui
travaillent sur un projet partagé — repose entièrement là-dessus : un projet propriété
d'une équipe n'a jamais les tickets d'une seconde équipe à faire apparaître, donc
l'élargissement n'est réel que pour un projet transverse et inerte partout ailleurs.

### Planification

Une dépendance est une flèche fin-à-début : le successeur ne peut pas commencer avant
la fin de son prédécesseur. Déplacer une date règle la **composante faiblement
connexe** autour du changement, et pas seulement les successeurs directs — un losange
comporte un ticket dont les deux prédécesseurs sont dans le rayon d'impact, et le
régler contre l'un des deux laisserait l'autre violé.

Trois règles décident de ce qui bouge, et chacune est une décision produit plutôt
qu'une optimisation :

1. **La marge est respectée.** Un successeur qui commence encore après la fin de son
   prédécesseur ne bouge pas, et la descente s'arrête là. Sans cela, chaque
   micro-ajustement ferait avancer tout le graphe et aucun ticket n'aurait jamais de
   marge, ce qui rendrait le chemin critique dénué de sens.
2. **Rien n'est jamais tiré en arrière.** Libérer de la marge ne ramène pas le
   travail dans le passé — personne ne s'y attend et personne ne pourrait l'annuler.
3. **Un ticket terminé ne bouge jamais.** Son arête est signalée violée à la place :
   un plan qui prétend tenir alors qu'il ne tient pas est le pire résultat possible.

Un ticket déplacé garde sa durée et seulement les bornes qu'il avait déjà : un jalon
— une seule borne, volontairement, typiquement une échéance sans début — ne se voit
donc pas pousser l'autre et devenir une période datée que personne n'a demandée.

Le chemin critique est calculé **par composante faiblement connexe**, jamais par
projet et jamais sur le périmètre visible : une chaîne peut traverser trois projets,
et s'ancrer sur ce qui se trouve à l'écran repeindrait des données identiques dès que
le filtre change.

Une date modifiée dans Notion passe par le même moteur. Le poller entrant écrit le
scalaire puis lance la cascade, donc une date saisie dans le miroir ne peut pas casser
le plan en silence.

### Persistance

Exposed pour le CRUD, avec Flyway propriétaire du schéma — pas de génération de DDL,
donc les migrations sont la définition unique de la base.

Six requêtes sont du SQL brut via le `JdbcClient` de Spring, parce que le DSL
Exposed ne peut pas les exprimer et que chacune est porteuse :

1. `WITH RECURSIVE` pour le sous-arbre d'équipes.
2. `UPDATE … FROM (… FOR UPDATE SKIP LOCKED)` pour prendre des jobs.
3. `ON CONFLICT … WHERE status = 'pending'` pour fusionner sur un index partiel.
4. `SELECT pg_notify(…)`.
5. `WITH RECURSIVE … UNION` pour la composante d'un graphe de dépendances. Le parcours
   ignore le sens des flèches, donc il revisite chaque nœud par les deux bouts —
   `UNION ALL` ne terminerait pas.
6. `WITH RECURSIVE` accumulant un `uuid[]` pour le chemin qu'une dépendance refusée
   refermerait. « Cycle détecté » tout seul n'est pas actionnable.

Elles tournent sur la connexion que Spring détient déjà, dans la même transaction que
les requêtes Exposed qui les entourent.

### Numérotation des tickets

`UPDATE teams SET ticket_counter = ticket_counter + 1 … RETURNING`, à l'intérieur de
la transaction d'insertion. Le verrou de ligne sur l'équipe sérialise les créations
concurrentes, donc deux personnes qui appuient sur `c` au même instant obtiennent 41
et 42 — pas de trous, pas de collisions, et aucune séquence par équipe à maintenir
alignée.

---

## Limites connues en v1

- Les pages créées dans Notion ne sont pas adoptées.
- La synchro entrante est scalaire uniquement (voir plus haut).
- Les sessions sont en mémoire : plus d'une instance d'API nécessite un store de
  session partagé (une propriété avec `spring-session-jdbc`).
- Pas de commentaires, pièces jointes, vues sauvegardées ni sous-tickets.
- Une réconciliation complète met en file au plus 500 tickets par appel et le dit
  dans les logs.
