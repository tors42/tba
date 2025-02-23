let lastSeenId = 0; // Ain't seen nothin' yet!
let reverseOrder = typeof reverse != 'undefined' && reverse != 0;
let maxNumMessages = typeof max != 'undefined' ? max : -1;

async function fetchMessages() {
    const divMessages = document.getElementById("messages");
    const resPromise = fetch('/messages?' + new URLSearchParams({
        lastSeenId: lastSeenId
    }).toString())

    const response = await resPromise;

    const numEvents = Number(response.headers.get("numEvents"));

    if (lastSeenId > numEvents) {
        toRemove = divMessages.children.length;
        while (toRemove-- > 0) {
            divMessages.removeChild(divMessages.firstChild);
        }
    }

    lastSeenId = numEvents;

    const text = await response.text();

    const wrapper = document.createElement('div');
    wrapper.innerHTML = text;

    /*
    console.log('Existing items : ' + divMessages.children.length);
    for (i = 0; i < divMessages.children.length; i++) {
        console.log('item : ' + i + ' - ' + divMessages.children.item(i).innerHTML);
    }

    console.log('New items : ' + wrapper.children.length);
    for (i = 0; i < wrapper.children.length; i++) {
        console.log('item : ' + i + ' - ' + wrapper.children.item(i).innerHTML);
    }
    */

    for (i = 0; i < wrapper.children.length; i++) {
        const child = wrapper.children.item(i);

        if (reverseOrder) {
            divMessages.appendChild(child.cloneNode(true));
        } else {
            divMessages.insertBefore(child.cloneNode(true), divMessages.firstChild);
        }
    }

    if (maxNumMessages > 0) {
        toRemove = divMessages.children.length - maxNumMessages;
        while (toRemove-- > 0) {
            if (reverseOrder) {
                divMessages.removeChild(divMessages.firstChild);
            } else {
                divMessages.removeChild(divMessages.lastChild);
            }
        }
    }
}

setInterval(fetchMessages , 1000);

fetchMessages();
