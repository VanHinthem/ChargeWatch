# ChargeWatch — startprompt voor elke nieuwe chat

Gebruik onderstaande prompt zonder hem telkens te hoeven aanpassen:

> We gaan verder met ChargeWatch: https://github.com/VanHinthem/ChargeWatch.
>
> Werk agentisch en pragmatisch volgens de actuele repository-instructies. Lees eerst `AGENTS.md`, `docs/STATUS.md`, `docs/WORKFLOW.md` en daarna relevante documentatie. Controleer actuele GitHub-issues, branches, open PR's en CI voordat je conclusies trekt.
>
> Bepaal waar we precies zijn gebleven en wat de logische eerstvolgende stap is. Werk zo zelfstandig mogelijk binnen de vastgelegde scope, in beheersbare features (geen microstappen of overbodige tests). Gebruik een featurebranch en PR; vraag mij altijd om inhoudelijke validatie voordat je merge of deployt. Merge nooit zonder mijn expliciete akkoord.
>
> Houd `docs/STATUS.md` en relevante documentatie actueel zodat een andere agent direct kan overnemen. Start met een korte samenvatting van huidige status en je voorgestelde volgende werkblok, en voer het uit als de scope al voldoende vastligt.

**Waarom dit werkt:** alle noodzakelijke projectcontext staat in GitHub; de prompt verwijst uitsluitend naar vaste paden. Hij hoeft niet te weten welke issue, feature of branch op dat moment actief is.

**Let op:** een niet-gemergede feature kan een recenter `docs/STATUS.md` op zijn branch hebben dan `main`. Nieuwe agent moet daarom ook open PR's bekijken. Vertel expliciet wanneer een gekozen statusdocument nog niet gemerged is.
