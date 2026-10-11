# Aspetto ModernUI

La modalità salvata rimane `pref_theme_mode_key` (LIGHT, DARK, SYSTEM).
`resolveDarkTheme` è una funzione pura condivisa tra Compose e icona Android.

La scelta iniziale usa `__APP_STATE_nyanime_initial_theme_choice_complete`, locale
al dispositivo ed esclusa dalle preferenze portabili. Non dipende dal numero di
versione: dopo la conferma gli aggiornamenti non la fanno ricomparire.

`ThemeSettingsRepository` osserva e salva le preferenze. L’adapter Android salva
modalità e completamento nello stesso commit su un thread IO e ripristina lo
stato in memoria se il commit fallisce. `ThemeController` serializza i cambi e
applica gli effetti Android solo dopo il salvataggio. Schermata iniziale e
impostazioni usano lo stesso controller e le stesse card prive di dipendenze IO.

`InitialThemeChoiceGate` precede la composizione del navigatore. La scelta in
corso è saveable; gli intent iniziali non vengono consumati fino alla conferma
e sopravvivono alla ricreazione dell’Activity. Il vecchio passaggio dei temi è
stato rimosso dall’onboarding, che conserva le altre configurazioni.

Palette Material e colori del marchio hanno ruoli separati. Le risorse native
day/night corrispondono alle palette Compose; un test confronta i ruoli principali
e verifica il contrasto del testo chiaro. Le sfumature delle copertine usano i
colori del tema senza ricolorare immagini, marchi delle fonti o pagine dei manga.

Gli alias LauncherRed e LauncherOrange puntano alla stessa MainActivity, che
rimane sempre abilitata per intent espliciti e collegamenti. Da Android 13 il
cambio degli alias è atomico; nelle versioni precedenti viene prima abilitato
l’alias selezionato. L’icona viene riconciliata all’avvio, al ritorno in primo
piano e ai cambi di configurazione, senza un servizio permanente. I launcher
possono aggiornare la propria cache con ritardo; le icone tematizzate seguono
la colorazione Android.
