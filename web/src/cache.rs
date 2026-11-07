//! In-memory lists (loaded once per session) + bouquet / vault rules + play queue.

use crate::api::{self, Category, Entry, Kind};
use crate::store;
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::rc::Rc;

#[derive(Clone)]
pub struct Data {
    pub cats: Rc<Vec<Category>>,
    pub entries: Rc<Vec<Entry>>,
}

thread_local! {
    static DATA: RefCell<HashMap<Kind, Data>> = RefCell::new(HashMap::new());
    static QUEUE: RefCell<Queue> = RefCell::new(Queue::default());
    static MULTI_FIRST: RefCell<Option<Entry>> = const { RefCell::new(None) };
}

pub fn get(kind: Kind) -> Option<Data> {
    DATA.with(|d| d.borrow().get(&kind).cloned())
}

pub fn clear() {
    DATA.with(|d| d.borrow_mut().clear());
}

/// Load categories + entries if not yet in memory.
pub async fn ensure(kind: Kind) -> Result<(), String> {
    if get(kind).is_some() {
        return Ok(());
    }
    let acc = store::account().ok_or("no account")?;
    let cats = api::categories(&acc, kind).await?;
    let entries = api::entries(&acc, kind).await?;
    DATA.with(|d| {
        d.borrow_mut().insert(
            kind,
            Data {
                cats: Rc::new(cats),
                entries: Rc::new(entries),
            },
        )
    });
    Ok(())
}

// ------------------------------------------------------------ bouquets

const ADULT: [&str; 6] = ["adult", "xxx", "18+", "porn", "erotik", "sex"];

/// Country/language prefix, e.g. "|TR| BEIN SPORTS" → "TR".
pub fn prefix(name: &str) -> Option<String> {
    let s = name.trim_start_matches(|c: char| !c.is_ascii_alphanumeric());
    let letters: String = s.chars().take_while(|c| c.is_ascii_alphabetic()).collect();
    let next = s.chars().nth(letters.chars().count());
    if (2..=4).contains(&letters.len()) && next.map(|c| !c.is_ascii_alphanumeric()).unwrap_or(false)
    {
        Some(letters.to_uppercase())
    } else {
        None
    }
}

/// Categories in the user's order (A–Z until the user changes it).
pub fn ordered_cats(kind: Kind) -> Vec<Category> {
    let Some(d) = get(kind) else { return vec![] };
    let mut by_name: Vec<Category> = d.cats.as_ref().clone();
    by_name.sort_by_key(|c| c.name.to_lowercase());
    let saved = store::cat_order(kind);
    if saved.is_empty() {
        return by_name;
    }
    let by_id: HashMap<&str, &Category> = d.cats.iter().map(|c| (c.id.as_str(), c)).collect();
    let mut out: Vec<Category> = saved
        .iter()
        .filter_map(|id| by_id.get(id.as_str()).map(|c| (*c).clone()))
        .collect();
    let seen: HashSet<String> = out.iter().map(|c| c.id.clone()).collect();
    out.extend(by_name.into_iter().filter(|c| !seen.contains(&c.id)));
    out
}

/// Manually locked + automatically detected adult categories.
pub fn locked_cats(kind: Kind) -> HashSet<String> {
    let mut s = store::locked_cats(kind);
    if store::settings().auto_lock_adult {
        if let Some(d) = get(kind) {
            for c in d.cats.iter() {
                let n = c.name.to_lowercase();
                if ADULT.iter().any(|a| n.contains(a)) {
                    s.insert(c.id.clone());
                }
            }
        }
    }
    s
}

pub fn is_vault(e: &Entry, vc: &HashSet<String>, vi: &HashSet<String>) -> bool {
    vc.contains(&e.cat) || vi.contains(&e.id)
}

/// Entries visible outside the vault (no hidden, no locked unless unlocked).
pub fn normal_entries(kind: Kind) -> Vec<Entry> {
    let Some(d) = get(kind) else { return vec![] };
    let hidden = store::hidden_cats(kind);
    let locked = if store::unlocked() {
        HashSet::new()
    } else {
        locked_cats(kind)
    };
    let (vc, vi) = (store::vault_cats(kind), store::vault_items(kind));
    d.entries
        .iter()
        .filter(|e| !hidden.contains(&e.cat) && !locked.contains(&e.cat) && !is_vault(e, &vc, &vi))
        .cloned()
        .collect()
}

pub fn vault_empty() -> bool {
    Kind::ALL
        .iter()
        .all(|k| store::vault_cats(*k).is_empty() && store::vault_items(*k).is_empty())
}

// ------------------------------------------------------------ play queue

#[derive(Clone, Default)]
pub struct Queue {
    pub kind: Option<Kind>,
    pub list: Rc<Vec<Entry>>,
    pub index: usize,
    /// Single URL (catch-up) instead of a list: (provider url, title)
    pub single: Option<(String, String)>,
}

pub fn set_queue(kind: Kind, list: Vec<Entry>, index: usize) {
    QUEUE.with(|q| {
        *q.borrow_mut() = Queue {
            kind: Some(kind),
            list: Rc::new(list),
            index,
            single: None,
        }
    });
}

pub fn set_single(url: String, title: String) {
    QUEUE.with(|q| {
        *q.borrow_mut() = Queue {
            kind: None,
            list: Rc::new(vec![]),
            index: 0,
            single: Some((url, title)),
        }
    });
}

pub fn queue() -> Queue {
    QUEUE.with(|q| q.borrow().clone())
}

/// Channel to put into the first multi-view window.
pub fn set_multi_first(e: Option<Entry>) {
    MULTI_FIRST.with(|m| *m.borrow_mut() = e);
}
pub fn take_multi_first() -> Option<Entry> {
    MULTI_FIRST.with(|m| m.borrow_mut().take())
}
