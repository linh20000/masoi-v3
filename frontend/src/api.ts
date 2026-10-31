export type Player = {id:string;name:string;connected:boolean;hasCard:boolean;revealed:boolean;roleKnown:boolean;revealedRoleId?:string|null;revealedRoleName?:string|null};
export type DeckRole = {roleId:string;name:string;faction:string;count:number};
export type Room = {code:string;hostName:string;status:string;version:number;createdAt:string;players:Player[];deck:DeckRole[]};
export type Session = {code:string;sessionToken:string;playerId:string|null;room:Room;kind:'host'|'player'};
export type SpecialChoiceState = {status?:string;targetPlayerId?:string;faction?:string;selectedRoleId?:string;selectedOption?:string;activeRoleId?:string;options?:string[]};
export type Role = {roleId:string;name:string;faction:string;description:string;imageUrl:string|null;sourceRoleId?:string;specialState?:SpecialChoiceState;revealed?:boolean};
export type SpecialRoleStatus = {playerId:string;playerName:string;roleId:string;status:string|null;targetPlayerId:string|null;targetName:string|null;faction:string|null;selectedRoleId:string|null;selectedRoleName:string|null;options:string[]};
const API=import.meta.env.VITE_API_URL || '/api';
const ERROR_MESSAGES:Record<string,string>={
  ROOM_CLOSED:'Phòng đã đóng hoặc không còn nhận người chơi.',
  NAME_TAKEN:'Tên này đã có người sử dụng trong phòng.',
  ROOM_FULL:'Phòng đã đủ số người tối đa.',
  CARD_NOT_DEALT:'Bạn chưa được phát bài trong phòng này.',
  SESSION_ACTIVE:'Không thể đổi bộ bài khi phiên đã bắt đầu. Hãy thu bài trước.',
  INVALID_DECK:'Cấu hình bộ bài không hợp lệ.',
  UNKNOWN_ROLE:'Vai trò đã chọn không tồn tại.',
  DECK_TOO_LARGE:'Bộ bài không thể vượt quá 40 lá.',
  ALREADY_DEALT:'Bài đã được phát. Hãy thu bài trước khi phát lại.',
  NO_PLAYERS:'Cần ít nhất một người chơi tham gia trước khi phát bài.',
  DECK_COUNT_MISMATCH:'Số lá bài phải bằng số người chơi đang kết nối. Hãy chỉnh lại số lượng vai.',
  EMPTY_DECK:'Hãy chọn lá bài trước khi phát.',
  PLAYER_NOT_IN_ROOM:'Người chơi được chọn không thuộc phòng này.',
  SESSION_NOT_DEALT:'Lựa chọn vai đặc biệt chỉ khả dụng sau khi phát bài.',
  UNKNOWN_SPECIAL_ROLE:'Vai đặc biệt không hợp lệ.',
  ROLE_MISMATCH:'Người chơi không có vai đặc biệt này.',
  INVALID_CHOICE:'Lựa chọn vai không hợp lệ.',
  INVALID_FACTION:'Phe được chọn không hợp lệ.',
  THIEF_OPTIONS_NOT_READY:'Quản trò chưa gửi các lá bài lựa chọn cho vai Ăn Trộm.',
  ACTOR_NOT_ACTIVE:'Vai Kịch Sĩ chưa sẵn sàng sử dụng lá bài.',
  INVALID_SPECIAL_ACTION:'Thao tác vai đặc biệt không hợp lệ.',
  PLAYER_CARD_NOT_FOUND:'Không tìm thấy lá bài của người chơi.',
  ACTOR_INVENTORY_ALREADY_PREPARED:'Kho bài Kịch Sĩ đã được chuẩn bị.',
  ACTOR_INVENTORY_TOO_SMALL:'Không đủ lá bài dự trữ cho Kịch Sĩ.',
  ACTOR_CARD_NOT_ACTIVE:'Không có lá Kịch Sĩ nào đang được sử dụng.',
  WILD_CHILD_IDOL_NOT_SET:'Đứa Trẻ Hoang Dã chưa chọn thần tượng.',
  SHADOW_TARGET_NOT_SET:'Ảnh Tử chưa chọn mục tiêu.',
  TARGET_CARD_MISSING:'Mục tiêu chưa có lá bài.',
  UNKNOWN_SPECIAL_ACTION:'Thao tác vai đặc biệt không được hỗ trợ.',
  INVALID_SPECIAL_STATE:'Không thể lưu trạng thái vai đặc biệt.',
  INVALID_TARGET:'Hãy chọn một người chơi khác trong phòng.',
  INVALID_ROLE_OPTIONS:'Các lá lựa chọn cần đúng số lượng, không trùng và hợp lệ.',
  ROOM_NOT_FOUND:'Không tìm thấy phòng theo mã này.',
  SESSION_REQUIRED:'Phiên phòng không hợp lệ hoặc đã hết hạn.',
  SESSION_INVALID:'Phiên này không có quyền truy cập phòng.',
  HOST_ONLY:'Chỉ quản trò mới có thể thực hiện thao tác này.',
  PLAYER_ONLY:'Thao tác này chỉ dành cho người chơi.',
  INVALID_NAME:'Tên phải có từ 2 đến 32 ký tự.',
  INTERNAL_ERROR:'Không thể hoàn tất yêu cầu. Vui lòng thử lại.'
};
async function request<T>(path:string, token?:string, options:RequestInit={}):Promise<T>{
  let response:Response;
  try{
    response=await fetch(`${API}${path}`,{...options,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{}),...options.headers}});
  }catch{
    throw new Error('Không thể kết nối đến máy chủ. Vui lòng kiểm tra kết nối rồi thử lại.');
  }
  if(!response.ok){
    let body:any={};
    try{body=await response.json()}catch{}
    const code=typeof body.code==='string'?body.code:'';
    throw new Error(ERROR_MESSAGES[code]||'Không thể hoàn tất yêu cầu. Vui lòng thử lại.');
  }
  if(response.status===204)return undefined as T;
  return response.json();
}
export const api={
  create:(hostName:string)=>request<Omit<Session,'kind'>>('/rooms',undefined,{method:'POST',body:JSON.stringify({hostName})}),
  join:(code:string,displayName:string)=>request<Omit<Session,'kind'>>(`/rooms/${encodeURIComponent(code)}/join`,undefined,{method:'POST',body:JSON.stringify({displayName})}),
  leave:(code:string,token:string)=>request<void>(`/rooms/${encodeURIComponent(code)}/leave`,token,{method:'POST'}),
  room:(code:string,token:string)=>request<Room>(`/rooms/${code}`,token),
  deck:(code:string,token:string,cards:{roleId:string;count:number}[])=>request<Room>(`/rooms/${code}/deck`,token,{method:'PUT',body:JSON.stringify({cards})}),
  deal:(code:string,token:string)=>request<Room>(`/rooms/${code}/deal`,token,{method:'POST'}),
  recall:(code:string,token:string,playerIds?:string[])=>request<Room>(`/rooms/${code}/recall`,token,{method:'POST',body:JSON.stringify({playerIds})}),
  end:(code:string,token:string)=>request<Room>(`/rooms/${code}/end`,token,{method:'POST'}),
  me:(code:string,token:string)=>request<Role>(`/rooms/${code}/me`,token),
  reveal:(code:string,token:string,revealed:boolean)=>request<void>(`/rooms/${encodeURIComponent(code)}/card-reveal`,token,{method:'POST',body:JSON.stringify({revealed})}),
  special:(code:string,token:string,roleId:string,choice:Record<string,string>)=>request<void>(`/rooms/${code}/special-choice`,token,{method:'POST',body:JSON.stringify({roleId,choice})}),
  specialRoles:(code:string,token:string)=>request<SpecialRoleStatus[]>(`/rooms/${code}/special-roles`,token),
  specialAction:(code:string,token:string,playerId:string,roleId:string,action:string,options:string[]=[] )=>request<void>(`/rooms/${code}/special-roles/${playerId}`,token,{method:'POST',body:JSON.stringify({roleId,action,options})})
};
export function socketUrl(code:string){const base=API.startsWith('http')?API.replace(/^http/,'ws').replace(/\/api$/,''):`${location.protocol==='https:'?'wss':'ws'}://${location.host}`;return `${base}/ws/rooms/${code}`;}
