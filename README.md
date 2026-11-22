# Ouvir Melhor

App Android que transforma o celular + fone Bluetooth num amplificador auditivo.
O microfone do celular capta o som, o app trata (equaliza, comprime, limpa o ruído,
amplifica) e manda para o fone em tempo real.

## O que o app tem

| Recurso | Para que serve |
|---|---|
| Botão grande Ligar/Desligar | Fácil para quem tem pouca familiaridade com celular |
| Volume (até +40 dB) | Amplificação principal |
| **Booster até 200%** | +10 dB extra (o ouvido percebe como o dobro do volume) |
| **Volume de cada ouvido** | Deixar um lado mais alto que o outro |
| **Teste de audição** | Bipes em 6 frequências por ouvido; o app calcula o reforço de cada ouvido (regra da meia-perda, estilo NAL) e mostra um audiograma |
| Programas | Natural, Conversa, TV, Restaurante, Música, Ao ar livre, Perda nos agudos |
| Equalizador de 6 bandas | 250 Hz a 8 kHz, ajuste fino à mão |
| Compressão WDRC | Sons fracos ficam mais altos sem sons fortes estourarem |
| **Redução de ruído com barra de intensidade** | Filtro espectral adaptativo (aprende ruídos constantes: bomba de piscina, ventilador, motor) + modo **IA** com rede neural RNNoise que separa a voz do barulho |
| Corte de graves | Tira vento, motor, batidas |
| Microfone: Voz / Ambiente / Apontar o celular | "Apontar" usa o microfone da câmera, bom para TV |
| Proteção do ouvido | Limitador com volume máximo ajustável |
| Para sozinho se o fone desconectar | Evita apito no alto-falante |
| Funciona com a tela apagada | Notificação com botão Desligar |

Android 8.0 ou mais novo.

## Como gerar o APK (grátis, dá para fazer pelo celular)

1. Crie uma conta em https://github.com (se ainda não tiver).
2. Crie um repositório novo (botão **+** → *New repository*), por exemplo `ouvir-melhor`.
3. Envie todos os arquivos desta pasta para ele (*Add file* → *Upload files*).
   Envie também a pasta `.github` (ela contém a receita que gera o APK).
   Se o envio pelo celular não aceitar essa pasta, use *Add file* → *Create new file*,
   digite o nome `.github/workflows/gerar-apk.yml` e cole o conteúdo desse arquivo.
4. Abra a aba **Actions**. O processo "Gerar APK" roda sozinho (uns 5 minutos).
5. Quando ficar verde, abra **Releases** (na página inicial do repositório)
   e baixe o **OuvirMelhor.apk**.

Pelo computador também dá: com Android Studio, abra a pasta e clique em *Run*,
ou rode `gradle assembleRelease`.

## Como instalar no celular

1. Abra o arquivo OuvirMelhor.apk baixado.
2. Se o celular pedir, permita "Instalar apps desconhecidos" para o navegador/arquivos.
3. Abra o app, conecte o fone Bluetooth, abaixe o volume e toque em **LIGAR**.
4. Recomendado: faça o **Teste de audição** em um lugar silencioso.

## Observações importantes

- O Bluetooth comum tem um atraso de ~0,15 a 0,3 s. É uma limitação do Bluetooth, não do app.
  Fone com fio (ou USB-C) não tem esse atraso.
- O teste de audição é aproximado: ele mede a forma da perda e a diferença entre os ouvidos,
  não substitui a audiometria com fonoaudiólogo.
- Comece sempre com volume baixo. Som alto por muito tempo pode piorar a audição.
- O arquivo `app/assinatura-teste.keystore` é uma chave de assinatura simples para o app
  poder ser atualizado por cima. Serve para uso pessoal; para publicar na Play Store, use uma chave própria.

## Créditos

Redução de ruído neural: [RNNoise](https://github.com/xiph/rnnoise) (Xiph.Org / Mozilla, licença BSD — ver `app/src/main/cpp/rnnoise/COPYING`).
