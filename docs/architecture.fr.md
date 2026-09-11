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
                          outbound_jobs (outbox) ──▶ Notion   (miroir)
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

**L'import est la seule exception, et il l'est parce qu'une personne est là.** L'import
Notion demande dans quelle équipe une base devient du travail, donc ce que le poller ne
peut pas fournir est fourni par la réponse ; le numéro vient ensuite du compteur de cette
équipe, comme pour n'importe quel ticket. Deux règles empêchent l'exception de refluer
vers le miroir : un ticket importé reçoit sa **propre** page dans `Kanso · Tickets`, et la
page source n'est jamais adoptée ni jamais écrite. L'adopter mettrait « Kanso gagne » aux
commandes d'un espace de travail que quelqu'un vient de confier, ce qui est la manière dont
un import efface ce qu'il importe. Une page que l'import ne peut toujours pas prendre — sans
titre, ou déjà archivée — est signalée avec son identifiant et sa raison plutôt que classée
sous un « Untitled » de plus.
---

## Là où le mapping Notion est lossy

| # | Problème | Ce que fait Kanso |
|---|---|---|
| 1 | Une propriété `people` n'accepte que des membres du workspace Notion : un utilisateur Kanso sans compte Notion ne peut pas y figurer. | Écrire `people` quand `users.notion_person_id` est connu, et toujours écrire en parallèle un rich-text `Assignees (Kanso)` avec le nom de tout le monde. Le miroir reste lisible ; la colonne texte est ignorée au retour. |
| 2 | Une relation exige que sa page cible existe déjà. | Les jobs portent une priorité de dépendance (équipe 10 → projet 20 → ticket 30 → doc 40) et un push dont la cible n'a pas encore de page est différé de quelques secondes au lieu d'échouer. |
| 3 | Écrire une relation remplace tout le tableau ; il n'y a pas de merge. | Chaque push écrit l'état complet de l'entité depuis Postgres. Cohérent avec « Kanso gagne », et c'est la raison pour laquelle aucun push partiel n'est tenté. |
| 4 | **Une relation ne peut pointer que vers des pages situées dans la data source qu'elle cible.** Les docs de Kanso sont des pages arbitraires ailleurs dans le workspace : les projets ne peuvent donc pas s'y relier directement. | La base miroir `Docs` contient une ligne d'index par page référencée, portant son URL, et les relations pointent vers ces lignes. `notion_docs` stocke donc deux ids : `notion_page_id` (la vraie page) et `mirror_page_id` (la ligne d'index). |
| 5 | Nos propres écritures déplacent `last_edited_time` et reviennent comme des changements. | Deux garde-fous, tous deux nécessaires : ignorer les pages dont le `last_edited_by` est notre bot, **et** ignorer tout ce qui n'est pas strictement plus récent que le `notion_last_edited_time` enregistré par le dernier push. Un humain peut éditer dans la même seconde que nous, d'où l'insuffisance d'un seul garde-fou. |
| 6 | Écrire une option `select` inconnue la crée silencieusement, donc le vocabulaire de statuts dérive. | Le vocabulaire est celui de l'*équipe*, et fermé tout de même : `V41` a remplacé `tickets_status_chk` par `tickets_status_fk` vers `team_statuses`, donc une clé que cette équipe n'a pas déclarée est refusée par Postgres. Une valeur inconnue qui revient de Notion est journalisée et ignorée, jamais adoptée — un miroir n'invente pas plus un statut qu'à l'époque où les six étaient un `CHECK`. |
| 7 | Notion n'a qu'une propriété date avec `start`/`end` ; une plage relue avec un seul `start` est ambiguë (date de début ou échéance ?). | **Écart délibéré par rapport à la spec initiale :** les tickets et les projets portent deux propriétés de date distinctes (`Start`, `Due` / `End`) plutôt qu'une plage. Moins joli dans les timelines Notion, mais l'aller-retour se fait sans deviner. |
| 8 | Notion archive, il ne supprime pas. | Une suppression Kanso archive la page ; une page déplacée dans la corbeille Notion passe `archived = true` dans Kanso. Il n'y a pas de suppression définitive miroir. |
| 9 | L'API 2025-09-03 imbrique les data sources sous les databases, et la référence de schéma publiée documente toujours `database_id` pour les relations. | Les ids de database et de data source sont tous deux découverts au bootstrap et persistés dans `notion_databases` — jamais devinés. Pour les configs de relation, la forme récente `data_source_id` est essayée en premier et retombe sur `database_id` en cas d'erreur de validation, en journalisant celle que le workspace a acceptée. |
| 10 | `Kanso ID` n'est pas unique côté Notion : une page dupliquée produit deux lignes revendiquant la même entité. | La réconciliation se fait d'abord par `notion_page_id` ; `Kanso ID` n'est qu'une clé de récupération. |
| 11 | La relation d'équipe auto-référencée de Notion accepte un cycle. | L'acyclicité est imposée dans Postgres avec `WITH RECURSIVE`, à l'aller comme au retour. Le parentage d'équipe n'est jamais accepté depuis Notion. |

Les lignes 1 à 11 parlent toutes du miroir : ce que Kanso écrit et relit dans ses quatre
bases, dont il a choisi chaque nom de colonne. L'import lit dans l'autre sens — un espace de
travail construit par quelqu'un d'autre, une seule fois — et il est lossy à sa manière, qui
n'a rien à voir avec celle du miroir.

| # | Problème | Ce que fait Kanso |
|---|---|---|
| 12 | **Le nom d'une colonne ne veut rien dire d'un workspace à l'autre.** Une base construite par quelqu'un qui n'a jamais entendu parler de Kanso appelle son statut `État` et ses options `En cours`. La correspondance stricte par nom qui sert le miroir importait un tel workspace en quatre cents tickets en `Todo`. | Les noms sont un pré-remplissage et jamais une règle : `ImportSchema` suggère, la troisième étape de l'écran 24 décide, et `MappedPageReader` lit une page *à travers* cette réponse. Le titre est la seule exception et se trouve par **type** — `title` est la seule propriété que Notion exige de toute base, et faire correspondre `"Name"` est précisément ce qui nommait « Untitled » les pages d'un workspace français. |
| 13 | Une option de select importée n'a aucune raison d'être un mot que Kanso connaît : `Terminé`, `Bloqué`, `P0`. | Chaque option d'une colonne mappée est à l'écran avec la valeur Kanso qu'elle prendra. Il faut deux choses pour que ce soit vrai, car `MappedPageReader` fait toujours correspondre le libellé d'une option quand le mapping ne dit rien : `ImportSchema` envoie la table d'options de **chaque** colonne candidate et non seulement de celle que sa suggestion par nom a trouvée, et la troisième étape amorce cette table dès qu'une colonne est choisie et affiche la correspondance par libellé — pas la valeur par défaut du champ — pour ce qui reste sur « — default — ». Les options qui prennent réellement la valeur par défaut du champ sont **nommées** plutôt que comptées : un nombre dit au lecteur qu'on a deviné quelque chose, une liste lui dit quoi. Une option dont Kanso n'a pas le mot prend cette valeur par défaut, jamais un septième statut que rien d'autre ne comprend. |
| 14 | Un rollup ou une formule n'a pas de colonne ici et pas de sens hors de Notion. | Conservé sous sa valeur *affichée* dans une section « imported from Notion » de la description, avec toutes les autres propriétés que rien n'a revendiquées. Volontairement lossy : une ligne lisible là vaut mieux qu'une copie fidèle des rouages de Notion dans une colonne qu'il faudrait ensuite maintenir en phase. |
| 15 | Une relation ne porte du sens que si ses deux bouts arrivent. Une colonne `Projet` qui pointe vers une base ignorée — ou importée dans le mauvais rôle — n'a rien à résoudre. | La deuxième étape le dit, à côté du décompte de ce que cela coûte, et propose d'importer l'autre base dans le rôle que cette relation demande. Ce n'est jamais bloquant : la ligne atterrit dans le repli donné à sa base, et la relation est comptée comme abandonnée dans le compte rendu. |
| 16 | Une relation `two_property` est déclarée des deux côtés et les deux peuvent se contredire — un ticket qui nomme le projet B alors que le projet A prétend le contenir. | La réponse de l'enfant gagne, parce que l'enfant est la ligne qu'on écrit, et le désaccord est compté dans le compte rendu plutôt que tranché en silence. La colonne inverse d'un parent (`Tâches` sur une base de projets) est un recours de dernier ressort, lue seulement là où l'enfant n'a rien dit. |
| 17 | Notion ne répond aucun total de pages pour une data source : un décompte est donc un parcours, à environ 2,5 requêtes par seconde. | Le parcours est borné par `kanso.notion.import.max-pages-per-database`, et une base plus longue rapporte le décompte atteint suivi d'un `+` — à la première étape, à la deuxième et sur le bouton de confirmation. Un nombre nu devant un bouton de confirmation serait un nombre faux. L'import ramène ensuite le préfixe qu'il a lu et rien au-delà de la borne ; la page [Follow-ups](https://github.com/Tykok/Kanso/wiki/Follow-ups) du wiki le lui reproche. |
| 18 | Une colonne `people` ne peut devenir une assignation que si un compte Kanso existe déjà pour cette personne. | La quatrième étape fait correspondre chaque personne Notion rencontrée sur une colonne mappée à un compte et *écrit* `users.notion_person_id`, si bien que la réponse tient pour tous les imports suivants et permet ensuite au miroir de remplir la propriété `people`. Qui reste sans correspondance laisse ses lignes non assignées plutôt que devinées, et créer des comptes reste le travail du flux d'invitation. |

### Connecter Notion

Une instance se connecte via une intégration **publique** et l'écran de consentement de
Notion, qui est aussi l'endroit où la personne choisit les pages que Kanso peut voir — donc
le partage qu'on faisait à la main depuis le menu `•••` d'une page se fait là, et le token
arrive par le fil au lieu d'être recopié. Ce qu'aucun fournisseur ne fera, c'est émettre un
client pour un hôte dont il n'a jamais entendu parler : créer l'intégration une fois et
coller son client id et son secret est donc l'étape qui survit ; c'est la même étape que
Google demande, pour la même raison.

Trois points du flux sont porteurs :

- `owner=user` dans l'URL d'autorisation est ce qui fait que Notion propose le sélecteur de
  pages. Sans lui, l'écran de consentement ne demande rien et n'accorde rien d'utile.
- Le secret du client authentifie l'échange du jeton en HTTP Basic. Il n'atteint jamais un
  navigateur, ce qu'un paramètre d'URL ferait — historique, logs de proxy, `Referer`.
- Le retour est Notion qui navigue le navigateur vers l'origine de Kanso, donc cette requête
  doit porter le cookie de session. `SameSite` est fixé à `lax` dans `application.yml`
  plutôt que laissé au défaut du navigateur, parce que `strict` casserait la connexion en
  silence, sur un réglage que personne ne penserait à relier à un bouton Notion. Derrière un
  reverse proxy, l'hôte transmis doit parvenir à l'application, sinon l'URI de redirection
  que Kanso construit ne correspondra pas à celle qui est enregistrée.

Coller un jeton d'intégration marche toujours et reste le repli documenté : une instance qui
a déjà une intégration interne fonctionnelle ne doit pas la refaire, et une instance dont le
navigateur ne peut pas atteindre un écran de consentement n'a pas d'autre entrée.

La page parente — celle sous laquelle Kanso crée ses quatre bases — se choisit dans une
liste au lieu de se nommer par identifiant. Une réserve mérite d'être connue : une recherche
filtrée sur les pages renvoie les *lignes* des bases de données, et chaque page que le
miroir écrit en est une, donc la liste exclut toute page dont le parent est une base ou une
source de données. C'est une règle sur la forme, pas une liste des identifiants de Kanso :
elle ne peut pas se périmer.

### La table des origines, et pourquoi ce n'est pas `notion_page_id`

`teams.notion_page_id`, `projects.notion_page_id` et `tickets.notion_page_id` contiennent
**la page du miroir** — la ligne que Kanso a créée dans `Kanso · Tickets` — et chaque push
sortant les écrase. Un import doit retenir un autre fait : de quelle page, dans l'espace de
travail de quelqu'un d'autre, une ligne Kanso a été faite.

Les deux faits se ressemblent assez pour partager une colonne, et c'est exactement pourquoi
ils ne doivent pas. Mettez l'id d'une page importée dans `notion_page_id` et le push suivant
pointe « Kanso gagne » vers l'espace de travail que quelqu'un vient de confier : l'état de
Kanso est écrit sur ses propres pages, et l'import efface ce qu'il importe. L'écran 24
promet que rien ne change dans Notion, et cette promesse meurt à l'instant où une colonne
veut dire les deux choses.

Le second fait a donc une table à lui, `V15__notion_import_origin.sql` :

```sql
CREATE TABLE notion_import_origin (
  notion_page_id TEXT PRIMARY KEY,
  entity_type    TEXT NOT NULL CHECK (entity_type IN ('team', 'project', 'ticket', 'doc')),
  entity_id      UUID NOT NULL,
  data_source_id TEXT NOT NULL,
  imported_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (entity_type, entity_id)
);

CREATE INDEX notion_import_origin_source_idx ON notion_import_origin (data_source_id);
```

`notion_page_id` est la clé primaire parce qu'une page Notion devient au plus une ligne
Kanso : la contrainte *est* la règle « importé une seule fois », imposée par Postgres plutôt
que par le fait de penser à vérifier — c'est ce qui rend l'import sûr à presser deux fois.
`entity_type` est le même vocabulaire fermé que celui du fil, tenu fermé ici par un `CHECK`.

Trois choses lisent cette table, et trois seulement :

1. **Résoudre une relation** vers une ligne importée lors d'une session **antérieure**, pour
   qu'un lien dont l'autre bout est arrivé le mois dernier reste silencieux au lieu de
   devenir une question.
2. **Reconnaître une page**, pour qu'un second import la laisse tranquille.
3. **Retrouver le projet conteneur d'une base de tâches** — celui que `TicketImport` nomme
   d'après la base, pour les tickets dont la relation n'a rien répondu. Cette ligne est
   clefée par l'id de *data source* de la base là où toutes les autres le sont par un id de
   page, et c'est le seul endroit où cette table est écrite deux fois : un conteneur que
   quelqu'un a supprimé depuis doit être remplacé par le nouveau, sinon la fois suivante on
   retrouverait l'id mort et on créerait un troisième conteneur.

`data_source_id` est écrit sur chaque ligne et, aujourd'hui, **lu par rien**. C'est ce dont un
import ultérieur aurait besoin pour dire « cette base a déjà été ramenée, 396 de ses 400
pages sont là », et `notion_import_origin_source_idx` est l'index que cette requête
utiliserait ; aucun écran ne la demande encore, et la page
[Follow-ups](https://github.com/Tykok/Kanso/wiki/Follow-ups) du wiki le dit plutôt que de
laisser la colonne passer pour porteuse.

Aucun des trois n'est un chemin entrant. Relire Notion dans une ligne existante est ce que
« Notion est en lecture seule dans les faits » refuse, et cela écraserait tout ce qui a été
fait dans Kanso depuis.

Il n'y a pas de clé étrangère, délibérément : la référence est polymorphe, et l'alternative
serait quatre colonnes nullables et un `CHECK` disant qu'une seule est remplie. Le prix est
qu'une entité supprimée laisse une ligne qui ne pointe plus sur rien : la graine que les
writers résolvent est donc d'abord filtrée sur les lignes vivantes — `ImportOriginRepository.live`,
une requête d'existence par sorte — et une ligne périmée se comporte alors exactement comme
une relation vers une base ignorée : elle ne résout rien et retombe sur le repli. Nettoyer ces
lignes est une entrée de la page [Follow-ups](https://github.com/Tykok/Kanso/wiki/Follow-ups)
du wiki, pas un trigger.

---

## Mécanismes

### L'outbox

Les lignes `outbound_jobs` sont insérées **dans la transaction métier**, si bien qu'un
crash juste après le commit ne peut pas perdre un push.

La file est générale : un job dit vers quelle `destination` il part et sur quel
`entity_type` il porte, deux axes plutôt qu'un genre composé. `OutboundWorker` draine
et réessaie, un `OutboundJobHandler` par destination dit ce qu'un job veut dire, et
Notion est pour l'instant le seul. Ajouter un consommateur, c'est un handler et une
valeur dans le vocabulaire `destination`, pas une deuxième file.

- Un index unique partiel n'autorise au plus qu'un job `pending` par
  `(destination, entité)`. Comme un push écrit la ligne entière, cinq pushs en file
  sont redondants — l'insert fusionne. `destination` est en tête de l'index pour
  qu'un ticket en file vers un système n'avale pas le même ticket en file vers un
  autre.
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

### Le journal d'activité, et pourquoi ce n'est pas le flux d'événements

`activity` enregistre ce qui s'est passé : un acteur, un genre pris dans un vocabulaire
fermé, et un payload `jsonb` portant l'avant et l'après d'un scalaire. Il est écrit par
les services qui publient déjà des événements — `TicketService` à la création, au patch,
à l'assignation et à l'archivage ; `CommentService` et `LabelService` sur leurs propres
écritures — **dans la transaction métier**, au même endroit que le changement lui-même.

C'est là toute la décision, et elle est l'inverse de celle du dessus. `EventPublisher`
émet après commit précisément pour que personne ne voie un changement avant qu'il soit
durable, ce qui veut dire qu'un récepteur qui n'écoutait pas n'apprend jamais qu'il a eu
lieu. Un journal bâti sur `pg_notify` serait donc lacunaire exactement dans le cas qu'il
existe pour expliquer : la coupure, le redémarrage, l'onglet qu'on a fermé. Donc le
journal est une table écrite dans la transaction, et le flux d'événements reste ce qu'il
est — une indication de refetch, qui ne porte aucun historique.

Les deux sont lus par des choses différentes et répondent à des questions différentes.
Une vue demande au flux *est-ce que quelque chose a changé* ; la page projet et la page
ticket demandent au journal *qu'est-ce qui a été fait ici*, du plus récent au plus
ancien. Rien ne dérive l'un de l'autre.

Deux conséquences à énoncer. Parce que le journal est transactionnel, `now()` ne peut pas
être son horloge : Postgres résout `now()` à l'horodatage de la transaction, donc toutes
les lignes qu'une transaction écrit seraient à égalité et l'ordre serait indéfini
précisément là où un fil en a besoin. `created_at` est écrit depuis Kotlin à la place. Et
parce que la suite de tests est `@Transactional` et rollback, aucun test n'atteint jamais
`pg_notify` — donc le journal s'assertit à travers `ActivityService`, jamais à travers un
événement.

Une ligne peut nommer un ticket privé, ce qui est la raison pour laquelle aucune
projection publique ne la lit.

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
volets, essayés dans l'ordre : l'acteur est `owner` ou `admin`, ce qui court-circuite
toute la règle, comme au paragraphe des rôles ci-dessus ; l'acteur est membre de
l'équipe du ticket **ou de l'un quelconque de ses ancêtres** ; à défaut des deux, une
**chaîne ouverte** — l'équipe du ticket et chacun de ses ancêtres, sans un seul membre
entre eux.

La clause de la chaîne ouverte n'est pas une garantie de migration, même si elle évite
bien le désastre de migration : déployer contre un `team_members` vide verrouillerait
sinon chaque board existant derrière un 403 dont le seul remède serait une invite SQL.
Mais c'est une conséquence de la clause, pas ce **pour quoi** elle existe.
`TeamService.create` n'enrôle jamais son créateur, et rien d'autre ne le fait non plus,
donc une équipe vide n'est pas un état que les instances laissent derrière elles — c'est
l'état dans lequel chaque équipe **naît**, et où elle reste jusqu'à ce que quelqu'un
pense à inviter des gens. La clause parcourt toute la chaîne parce que la question posée
est « quelqu'un, où que ce soit au-dessus de ce ticket, a-t-il revendiqué ce travail » —
un seul ancêtre peuplé répond à cette question pour tous ses descendants à la fois. Une
sous-équipe créée sous une organisation déjà peuplée est gouvernée dès l'instant où elle
existe : c'est le cas courant, et la chaîne le referme sans que personne ait à se
souvenir d'une étape. Une équipe **racine** reste ouverte jusqu'à ce qu'elle-même,
spécifiquement, obtienne un membre — ce qui est à la fois le cas de migration (le
`team_members` de toute instance préexistante est vide le jour où ceci est déployé) et
la réponse honnête à « personne n'a encore revendiqué ce travail » : on crée l'équipe,
puis on invite les gens, et le board reste ouvert entre les deux.

Une équipe entre dans l'état ouvert au moment où `TeamService.create` la retourne, et en
sort au moment où quelqu'un — l'équipe elle-même ou l'un quelconque de ses ancêtres —
obtient un premier membre. Il n'y a pas de troisième porte, et rien à retenir : c'est la
règle de la chaîne qui referme d'elle-même la porte d'une sous-équipe.

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

### Les lectures sont ouvertes, les écritures sont cadrées

Aucun `GET` dans Kanso n'est cadré par équipe. `/api/tickets`, `/api/projects`,
`/api/timeline` et le roster de chaque équipe répondent à n'importe quel utilisateur
authentifié pour n'importe quelle équipe, et la timeline transverse que ce projet vient
de construire repose exactement là-dessus : une ligne de contexte dessine un ticket que
le lecteur n'a peut-être pas le droit de déplacer, et le chemin critique parcourt les
arêtes sans demander qui possède l'une ou l'autre extrémité.

Le cadrage des lectures par équipe a été envisagé et refusé, pas simplement jamais
proposé. Il rendrait les lignes de contexte de la timeline contradictoires — une ligne
existe pour montrer qui d'autre fait quoi, et cacher une partie de ce « qui d'autre »
retire la raison pour laquelle la fonctionnalité a été construite. Il mettrait le chemin
critique en position de traverser des tickets que le lecteur ne peut pas voir, et il
faudrait répondre à la question de ce à quoi ressemble une barre cachée sur un graphique
qui, sinon, ne passe jamais sous silence ce qui existe. Aucune de ces trois questions
n'est un correctif mineur, et toutes trois découlent du cadrage d'un seul point d'accès,
pas de celui de tous.

La posture est donc délibérée et à sens unique : **tout est lisible, seules les
écritures sont cadrées.** `TicketAccess` filtre `create`, `patch`, `delete`, et chaque
déplacement d'équipe — chaque écriture — et ne filtre rien qui ne fasse que lire. Le
seul écran qui contredisait cela, `MembersSection` cachant le roster à un simple membre
alors que le point d'accès derrière répondait quand même, a été remis en cohérence plutôt
que gardé comme exception : un simple membre voit désormais le roster aussi, en lecture
seule, les contrôles d'ajout et de suppression restant filtrés comme avant.

C'est ce qui doit empêcher la prochaine personne de « corriger » un point d'accès isolé :
cadrer un seul `GET` ne rendrait pas Kanso plus privé — tous les autres points d'accès
répondraient toujours à la même question — cela le rendrait seulement incohérent, à un
coût que la timeline a déjà payé pour éviter.

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

Sept requêtes sont du SQL brut via le `JdbcClient` de Spring, parce que le DSL
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
7. `CAST(:payload AS jsonb)` sur l'insertion d'activité. Le driver envoie une chaîne
   Kotlin en `varchar`, ce que Postgres refuse pour une colonne `jsonb` ; `outbound_jobs`
   caste de la même façon. Les lectures repassent par Exposed.

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
- Pas de pièces jointes ni de sous-tickets. Les commentaires, les mentions et les
  étiquettes existent depuis `V8` ; les vues sauvegardées non.
- Une réconciliation complète met en file au plus 500 tickets par appel et le dit
  dans les logs.
