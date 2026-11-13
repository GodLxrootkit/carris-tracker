# Carris Tracker

> **Este é um projeto de passatempo.** Foi feito sem revisão formal e não é afiliado nem
> apoiado pela Carris Metropolitana, pela CARRIS, pela CP ou pelo Metro de Lisboa. Use por sua
> conta e risco.

Uma aplicação Android para esperar autocarros e comboios em Lisboa e arredores.

Responde a duas perguntas: **quando passa o próximo veículo nas paragens que uso** e **onde
estão os veículos das linhas que sigo**. Junta quatro redes numa só app:

- **Carris Metropolitana** — autocarros na área metropolitana de Lisboa;
- **CARRIS** — autocarros do município de Lisboa;
- **CP** — comboios, com horários;
- **Metro de Lisboa** — quatro linhas, com horários.

A rede **TTSL** aparece na app como «em preparação» e ainda não tem dados.

O mapa em tempo real só está disponível na rede CARRIS. Nas outras redes, o separador do mapa
fica esbatido e explica porquê.

A interface está em português.

## Funcionalidades

### Paragens — as suas paragens

- Separadores por rede: **Carris**, **Metro**, **CP** e **TTSL** (em preparação).
- Guardar paragens por pesquisa de nome ou pela mais próxima de si.
- Cada paragem mostra as próximas passagens: linha, destino e contagem decrescente.
- Nas redes com posições em tempo real (**Carris Metropolitana** e **CARRIS**), a contagem é
  calculada a partir da posição real do veículo. Uma contagem **vermelha** significa previsão
  com veículo ao vivo; uma **hora simples** significa apenas horário.
- Nas redes **CP** e **Metro**, os horários vêm dos ficheiros GTFS incluídos na app; não há
  posições em tempo real.
- No **Metro**, as estações de correspondência aparecem uma só vez (por exemplo, Alameda
  mostra as linhas C e D juntas) e são mostradas até **4 partidas por linha e sentido**. Nas
  restantes redes são mostradas 2 por linha e sentido, para nenhum destino esconder o outro.
- As linhas do Metro usam as cores oficiais: Azul, Amarela, Verde e Vermelha.
- A estação de Metro é filtrada pelo dia da semana: horário de dia útil e de fim de semana não
  se misturam, e partidas iguais (mesma linha, destino e minuto) aparecem uma só vez.
- Colapsar um cartão para ver um resumo numa linha; manter premido para reordenar as paragens.
- Editar ou remover paragens guardadas.
- **Notificação de aproximação** quando um veículo está a menos de 5 minutos de uma paragem
  guardada.
- As últimas chegadas ficam em cache: se a rede falhar, a app mostra a última informação que
  tinha em vez de ficar vazia.

### Mapa — o mapa ao vivo

- Disponível apenas na rede **CARRIS**; nas outras redes o separador fica esbatido e mostra
  «O mapa só está disponível na rede Carris.».
- Todos os autocarros CARRIS das linhas que segue, desenhados como um ponto na cor da linha e
  com o número por cima. Toque num autocarro para ver o destino.
- O traçado de cada linha seguida, as paragens guardadas e a sua posição.
- Filtro por linha, com o número de veículos de cada uma, e um botão para enquadrar todos os
  autocarros.
- O mapa abre com zoom de rua (15) e afasta-se para enquadrar os seus pontos quando não há
  veículos.
- As posições CARRIS são atualizadas cerca de cada 15 segundos, ao ritmo a que a operadora
  publica. A app deixa de consultar quando está em segundo plano.
- Mapas © OpenStreetMap contributors.

### Definições — a app à sua medida

- **Escolher as linhas que quer seguir**, de todas as redes: 717 linhas da Carris
  Metropolitana, 176 rotas da CARRIS, 184 rotas da CP e as 4 linhas do Metro, numa lista
  pesquisável.
- A pesquisa de linhas ignora acentos: escrever `alcacer` ou `algacs` encontra `Alcácer` ou
  `Algács`.
- **Uma instalação nova começa sem linhas escolhidas.** Quem já tinha a app mantém a seleção
  que tinha, incluindo as antigas linhas predefinidas.
- A escolha fica guardada entre arranques. O mapa, as chegadas e os filtros passam a mostrar
  só essas linhas.
- Algumas rotas aparecem esbatidas e com o motivo: os dados de distância da operadora estão
  errados, por isso qualquer previsão baseada neles seria falsa.
- **Atualizações dentro da app.** A opção «Procurar atualizações ao abrir a app» está ligada
  por omissão e pode ser desligada. Também pode procurar manualmente com «Procurar agora».
- Uma versão nova é oferecida para transferir e instalar; nada é transferido nem instalado sem
  a sua confirmação.
- O APK transferido é verificado contra o certificado de assinatura da app. Um ficheiro com
  outra assinatura é apagado em vez de ser instalado.
- Uma versão que recusou fica memorizada, para o arranque seguinte não voltar a perguntar.
- Um aviso de versão nova aparece acima dos separadores, com atalho para Definições.
- Secção «Sobre» com a versão instalada e quantas linhas está a seguir.
- O tema claro ou escuro segue automaticamente o sistema.

## Privacidade

- Sem conta, sem início de sessão, sem análises, sem publicidade.
- **Não são necessárias nem guardadas chaves de API.** Os operadores publicam feeds públicos e
  a app lê-os diretamente.
- As paragens guardadas, as linhas escolhidas e a cache de chegadas ficam no seu dispositivo e
  nunca são enviadas para lado nenhum.
- A localização é usada apenas para encontrar a paragem mais próxima e para mostrar onde está
  no mapa. Pode recusar a permissão; o mapa e os horários continuam a funcionar.
- A verificação de atualizações consulta as releases públicas do projeto no GitHub. Só acontece
  se a opção estiver ligada ou se tocar em «Procurar agora».

## Instalação

Transfira `carris-tracker-release.apk` da
[página de releases](https://github.com/GodLxrootkit/carris-tracker/releases) e abra o ficheiro.
O Android pede uma vez autorização para instalar a partir dessa origem.

Requer Android 7.0 (API 24) ou mais recente. A instalação por cima de uma versão anterior
funciona porque todas as versões são assinadas com a mesma chave; se a assinatura não
corresponder, o Android recusa a instalação.

## Capturas de ecrã

Ainda não existem. Para adicionar, coloque PNGs em `docs/screenshots/` e referencie-os aqui:

```markdown
![Paragens](docs/screenshots/paragens.png)
```

## De onde vêm os dados

| Rede | Área | Linhas | Horários | Posições em tempo real | Chave de API |
|---|---|---|---|---|---|
| Carris Metropolitana | área metropolitana de Lisboa | 717 | API pública | API pública | não |
| CARRIS | município de Lisboa | 176 rotas | GTFS | GTFS-RT | não |
| CP | comboios | 184 rotas | GTFS | não | não |
| Metro de Lisboa | Lisboa | 4 linhas | GTFS | não | não |
| TTSL | em preparação | — | — | — | — |

Os ficheiros GTFS da CARRIS, da CP e do Metro são incluídos no APK para a app funcionar também
sem rede. Os da CARRIS são atualizados diariamente e os da CP e do Metro semanalmente; o release
semanal leva esses dados novos para o telemóvel.

Mapas © OpenStreetMap contributors.

Esta é uma aplicação independente e não oficial.

## Compilar

Ver **[BUILD.md](BUILD.md)** para a versão curta:

```bash
./build.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Para assinar um release precisa da sua própria keystore; as instruções estão em BUILD.md.
**Nunca faça commit de uma keystore nem das suas palavras-passe** — as chaves usadas nos APKs
publicados não estão no repositório.

### Releases e dados

Quatro workflows tratam dos dados e dos releases. Nenhum deles vê a chave de assinatura como
ficheiro:

- **`ci.yml`** corre os testes e compila em cada push e pull request.
- **`refresh-carris-asset.yml`** corre todos os dias às 05:23 UTC, regenera os dados da CARRIS e
  só faz commit quando o feed mudou.
- **`refresh-cp-metro-assets.yml`** corre aos sábados às 07:17 UTC, descarrega os feeds da CP e
  do Metro, regenera os assets e só faz commit quando algum ficheiro mudou.
- **`weekly-release.yml`** corre aos domingos às 12:00 UTC, sobe a `versionName` pelo incremento
  mínimo (e a `versionCode` com ela), compila o APK assinado, confirma o certificado contra a
  impressão digital compilada na app e publica-o como release. É este passo que entrega aos
  telemóveis os dados atualizados durante a semana.

A chave chega ao runner como segredo do repositório, é materializada em `build/` (que está no
`.gitignore`) e apagada no fim. Antes de publicar, o workflow recusa continuar se o APK não
estiver assinado com o certificado esperado — a mesma verificação que o atualizador da app faz
no dispositivo.

## Mais

- **[CHANGELOG.md](CHANGELOG.md)** — o que mudou em cada release.
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — como as chegadas são previstas e as partes
  estranhas das fontes de dados.

## Licença

MIT — ver **[LICENSE](LICENSE)**. Pode usar, fazer fork, publicar comercialmente e alterar. A
única exigência é manter o aviso de copyright.
