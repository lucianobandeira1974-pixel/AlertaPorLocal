# Alerta por Local (Android)

Projeto inicial nativo Android em Kotlin. Abra a pasta no Android Studio e sincronize o Gradle.

## O que faz
- Converte um endereço em coordenadas usando o geocoder do Android.
- Permite configurar mensagem e raio em metros (10 a 10.000 m).
- Monitora a localização em um serviço em primeiro plano.
- Ao entrar no raio, abre uma tela vermelha com mensagem grande e alarme sonoro.
- O botão **PARAR ALARME** silencia o alerta.
- O monitoramento é rearmado após detectar que o usuário saiu do raio, para alertar novamente na próxima entrada.
- O serviço mantém uma notificação persistente enquanto monitora.

## Permissões e configuração
Para funcionar com a tela apagada, conceda localização precisa e, nas configurações do Android, permita localização **o tempo todo**. Permita notificações e notificações em tela cheia quando o Android oferecer essa opção. Desative otimizações de bateria para o app se o fabricante encerrar o serviço.

## Limitações importantes desta versão inicial
- Usa a última localização conhecida e verifica aproximadamente a cada 10 segundos; o tempo de disparo e a precisão dependem do GPS, da rede, do Android e do fabricante.
- O geocoding depende de conexão/serviço de localização de endereços.
- Android 10+ exige permissão de localização em segundo plano; o fluxo de permissão deve ser finalizado nas Configurações.
- Android 13+ exige permissão de notificações. Android 14+ pode limitar notificações em tela cheia conforme as configurações do usuário.
- O serviço pode ser interrompido pelo sistema ou pelo usuário, especialmente após reinicialização, força-parada ou restrições de bateria.
- Para distribuição pública, recomenda-se implementar solicitação de permissões por etapas, recuperar serviço após reinicialização, testar em aparelhos reais e considerar a API Geofencing do Google Play Services para maior eficiência energética.

## Como testar
1. Abra no Android Studio.
2. Execute em aparelho Android com GPS ativo.
3. Conceda permissões.
4. Configure um endereço real, uma mensagem e um raio.
5. Saia do raio e entre novamente para testar o rearme.
